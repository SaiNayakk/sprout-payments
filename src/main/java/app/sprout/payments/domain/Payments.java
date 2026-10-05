package app.sprout.payments.domain;

import app.sprout.payments.config.PaymentsProperties;
import app.sprout.payments.domain.Upstreams.Leg;
import app.sprout.payments.domain.Upstreams.Reply;
import app.sprout.payments.domain.Upstreams.Unreachable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Moving money between Sprout Bank and Sprout. The ledger holds the money; this service holds each
 * payment's story and drives it to an end state.
 *
 * <p>Three rules keep money from being lost or made up:
 * <ul>
 *   <li>Every ledger entry and bank instruction carries the payment's id as its idempotency key or
 *       reference, so any step can be repeated safely.</li>
 *   <li>The bank's approval is the truth: an approved collect request is credited whatever this
 *       service thought of the deposit before.</li>
 *   <li>A withdrawal holds the money in the ledger before the bank is asked to pay, and only settles
 *       or releases the hold once the bank has given a definite answer. Unknown outcomes are retried
 *       by the reconciler, never guessed.</li>
 * </ul>
 */
@Service
public class Payments {

    private static final Logger log = LoggerFactory.getLogger(Payments.class);

    public record Deposit(UUID id, UUID userId, long amount, String status, UUID bankRequestId, String failureReason,
                          Instant createdAt, Instant updatedAt) {}

    public record Withdrawal(UUID id, UUID userId, long amount, String payeeVpa, String status, String failureReason,
                             Instant createdAt, Instant updatedAt) {}

    public record Created<T>(T value, boolean created) {}

    private final JdbcClient db;
    private final Clock clock;
    private final Upstreams up;
    private final long minimum;
    private final long maximum;

    public Payments(JdbcClient db, Clock clock, Upstreams up, PaymentsProperties props) {
        this.db = db;
        this.clock = clock;
        this.up = up;
        this.minimum = Money.paise(props.minimum());
        this.maximum = Money.paise(props.maximum());
    }

    static String cash(UUID user) {
        return "customer:" + user + ":cash";
    }

    static String hold(UUID user) {
        return "customer:" + user + ":withdrawal-hold";
    }

    private long amount(String rupees) {
        long paise = Money.paise(rupees);
        if (paise < minimum || paise > maximum) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Each payment is between ₹" + Money.rupees(minimum) + " and ₹"
                    + Money.rupees(maximum) + ".");
        }
        return paise;
    }

    private static void checkKey(String key) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send an Idempotency-Key header (8 to 100 characters, e.g. a UUID).");
        }
    }

    // ── balance ──────────────────────────────────────────────────────────────

    public long[] balance(UUID user) {
        up.account(user);
        try {
            return new long[] {up.balance(cash(user)), up.balance(hold(user))};
        } catch (Unreachable e) {
            throw Upstreams.unavailable();
        }
    }

    // ── deposits ─────────────────────────────────────────────────────────────

    public Created<Deposit> deposit(UUID user, String key, String rupees) {
        checkKey(key);
        long paise = amount(rupees);
        Optional<Deposit> earlier = depositByKey(user, key);
        if (earlier.isPresent()) {
            return new Created<>(earlier.get(), false);
        }
        String vpa;
        try {
            vpa = up.account(user).bankVpa();
        } catch (Unreachable e) {
            throw Upstreams.unavailable();
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            db.sql("INSERT INTO deposits (id, user_id, idempotency_key, amount_paise, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'AWAITING_APPROVAL', ?, ?)")
                    .params(id, user, key, paise, ts(now), ts(now)).update();
        } catch (DuplicateKeyException e) {
            return new Created<>(depositByKey(user, key).orElseThrow(), false);
        }
        try {
            Reply r = up.collect(vpa, paise, id.toString());
            if (r.ok()) {
                db.sql("UPDATE deposits SET bank_request_id = ?, updated_at = ? WHERE id = ?")
                        .params(UUID.fromString(r.body().path("id").asText()), ts(clock.instant()), id).update();
            } else {
                fail(id, "Sprout Bank refused the request (" + r.code() + ").");
            }
        } catch (Unreachable e) {
            // The bank may or may not have the request. If the customer approves it anyway, the bank's
            // callback still credits them: an approval always wins (see receive()).
            fail(id, "Sprout Bank couldn't be reached. Nothing was taken; try again.");
        }
        return new Created<>(deposit(id), true);
    }

    private void fail(UUID id, String reason) {
        db.sql("UPDATE deposits SET status = 'FAILED', failure_reason = ?, updated_at = ? WHERE id = ? AND status = 'AWAITING_APPROVAL'")
                .params(reason, ts(clock.instant()), id).update();
    }

    /**
     * A bank callback, already authenticated. Returns normally once applied (or if it was already
     * applied); throws if it can't be applied now, so the bank keeps the event and retries.
     */
    public void receive(UUID eventId, String type, UUID requestId, String reference, long paise) {
        if (db.sql("SELECT 1 FROM bank_events WHERE event_id = ?").param(eventId).query(Integer.class).optional().isPresent()) {
            return;
        }
        UUID depositId;
        try {
            depositId = UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            log.warn("Bank event {} for unknown reference {}; ignoring", eventId, reference);
            remember(eventId);
            return;
        }
        Optional<Deposit> d = db.sql(DEPOSIT_SQL + " WHERE id = ?").param(depositId).query(Payments::deposit).optional();
        if (d.isEmpty()) {
            log.warn("Bank event {} for a deposit we don't have ({}); ignoring", eventId, reference);
            remember(eventId);
            return;
        }
        apply(d.get(), type, requestId, paise);
        remember(eventId);
    }

    private void apply(Deposit d, String outcome, UUID requestId, long paise) {
        switch (outcome) {
            case "COLLECT_APPROVED", "APPROVED" -> {
                if (paise != d.amount()) {
                    log.error("Bank approved {} for deposit {} of {}; crediting what the bank moved", Money.rupees(paise), d.id(),
                            Money.rupees(d.amount()));
                }
                Reply r = up.post("deposit:" + d.id(), "Money added from Sprout Bank", d.id().toString(),
                        List.of(new Leg("sprout:bank", "DEBIT", paise), new Leg(cash(d.userId()), "CREDIT", paise)));
                if (!r.ok()) {
                    throw new IllegalStateException("ledger refused deposit " + d.id() + ": " + r.code());
                }
                db.sql("UPDATE deposits SET status = 'COMPLETED', bank_request_id = COALESCE(bank_request_id, ?), failure_reason = NULL, "
                        + "updated_at = ? WHERE id = ?").params(requestId, ts(clock.instant()), d.id()).update();
            }
            case "COLLECT_DECLINED", "DECLINED", "COLLECT_EXPIRED", "EXPIRED" -> {
                String status = outcome.endsWith("DECLINED") ? "DECLINED" : "EXPIRED";
                db.sql("UPDATE deposits SET status = ?, updated_at = ? WHERE id = ? AND status IN ('AWAITING_APPROVAL', 'FAILED')")
                        .params(status, ts(clock.instant()), d.id()).update();
            }
            default -> log.warn("Unknown bank outcome {} for deposit {}", outcome, d.id());
        }
    }

    private void remember(UUID eventId) {
        db.sql("INSERT INTO bank_events (event_id, received_at) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(eventId, ts(clock.instant())).update();
    }

    /** Deposits waiting longer than they should: ask the bank directly, in case a callback was lost. */
    public int reconcileDeposits(Instant olderThan) {
        List<Deposit> waiting = db.sql(DEPOSIT_SQL + " WHERE status = 'AWAITING_APPROVAL' AND bank_request_id IS NOT NULL "
                + "AND updated_at < ? ORDER BY updated_at LIMIT 50").param(ts(olderThan)).query(Payments::deposit).list();
        int settled = 0;
        for (Deposit d : waiting) {
            try {
                Reply r = up.collectStatus(d.bankRequestId());
                String status = r.ok() ? r.body().path("status").asText() : "";
                if (!status.equals("PENDING") && !status.isEmpty()) {
                    apply(d, status, d.bankRequestId(), Money.paise(r.body().path("amount").asText()));
                    settled++;
                } else {
                    db.sql("UPDATE deposits SET updated_at = ? WHERE id = ?").params(ts(clock.instant()), d.id()).update();
                }
            } catch (RuntimeException e) {
                log.info("Couldn't reconcile deposit {} yet: {}", d.id(), e.getMessage());
            }
        }
        return settled;
    }

    public Deposit deposit(UUID id) {
        return db.sql(DEPOSIT_SQL + " WHERE id = ?").param(id).query(Payments::deposit).single();
    }

    public Deposit myDeposit(UUID user, UUID id) {
        return db.sql(DEPOSIT_SQL + " WHERE id = ? AND user_id = ?").params(id, user).query(Payments::deposit).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such deposit of yours."));
    }

    public List<Deposit> deposits(UUID user) {
        return db.sql(DEPOSIT_SQL + " WHERE user_id = ? ORDER BY created_at DESC LIMIT 100").param(user).query(Payments::deposit).list();
    }

    private Optional<Deposit> depositByKey(UUID user, String key) {
        return db.sql(DEPOSIT_SQL + " WHERE user_id = ? AND idempotency_key = ?").params(user, key).query(Payments::deposit).optional();
    }

    // ── withdrawals ──────────────────────────────────────────────────────────

    public Created<Withdrawal> withdraw(UUID user, String key, String rupees) {
        checkKey(key);
        long paise = amount(rupees);
        Optional<Withdrawal> earlier = withdrawalByKey(user, key);
        if (earlier.isPresent()) {
            return new Created<>(earlier.get(), false);
        }
        String vpa;
        try {
            vpa = up.account(user).bankVpa();
        } catch (Unreachable e) {
            throw Upstreams.unavailable();
        }
        UUID id = UUID.randomUUID();
        // 1. hold the money: the ledger refuses if it isn't there, so it can't be spent twice
        Reply held;
        try {
            held = up.post("withdrawal-hold:" + id, "Withdrawal to " + vpa, id.toString(),
                    List.of(new Leg(cash(user), "DEBIT", paise), new Leg(hold(user), "CREDIT", paise)));
        } catch (Unreachable e) {
            throw Upstreams.unavailable();
        }
        if (held.status() == 422 && held.code().equals("INSUFFICIENT_FUNDS")) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "That's more than your available balance. Nothing moved.");
        }
        if (!held.ok()) {
            throw Upstreams.unavailable();
        }
        Instant now = clock.instant();
        try {
            db.sql("INSERT INTO withdrawals (id, user_id, idempotency_key, amount_paise, payee_vpa, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?)").params(id, user, key, paise, vpa, ts(now), ts(now)).update();
        } catch (DuplicateKeyException e) {
            // the same request raced in and won: give this hold back and answer with the winner
            up.post("withdrawal-release:" + id, "Duplicate withdrawal request", id.toString(),
                    List.of(new Leg(hold(user), "DEBIT", paise), new Leg(cash(user), "CREDIT", paise)));
            return new Created<>(withdrawalByKey(user, key).orElseThrow(), false);
        }
        // 2. pay, then settle or release; an unknown outcome stays PROCESSING for the reconciler
        advance(withdrawal(id));
        return new Created<>(withdrawal(id), true);
    }

    private void advance(Withdrawal w) {
        try {
            pay(w);
        } catch (Unreachable e) {
            // the bank or the ledger didn't answer: the outcome is unknown, so leave it for the reconciler
            log.info("Withdrawal {} not settled yet ({}); will retry", w.id(), e.getMessage());
        }
    }

    private void pay(Withdrawal w) {
        Reply paid = up.payout(w.payeeVpa(), w.amount(), w.id().toString());
        if (paid.ok()) {
            Reply settled = up.post("withdrawal-settle:" + w.id(), "Paid to " + w.payeeVpa(), w.id().toString(),
                    List.of(new Leg(hold(w.userId()), "DEBIT", w.amount()), new Leg("sprout:bank", "CREDIT", w.amount())));
            if (settled.ok()) {
                finish(w.id(), "COMPLETED", null);
            }
            return;
        }
        // a definite refusal (unknown address, the bank's rules): give the money back
        Reply released = up.post("withdrawal-release:" + w.id(), "Withdrawal refused by Sprout Bank", w.id().toString(),
                List.of(new Leg(hold(w.userId()), "DEBIT", w.amount()), new Leg(cash(w.userId()), "CREDIT", w.amount())));
        if (released.ok()) {
            finish(w.id(), "FAILED", "Sprout Bank refused the payout (" + paid.code() + "). The money is back in your balance.");
        }
    }

    private void finish(UUID id, String status, String reason) {
        db.sql("UPDATE withdrawals SET status = ?, failure_reason = ?, updated_at = ? WHERE id = ? AND status = 'PROCESSING'")
                .params(status, reason, ts(clock.instant()), id).update();
    }

    public int reconcileWithdrawals(Instant olderThan) {
        List<Withdrawal> stuck = db.sql(WITHDRAWAL_SQL + " WHERE status = 'PROCESSING' AND updated_at < ? ORDER BY updated_at LIMIT 50")
                .param(ts(olderThan)).query(Payments::withdrawal).list();
        int done = 0;
        for (Withdrawal w : stuck) {
            try {
                advance(w);
                if (!withdrawal(w.id()).status().equals("PROCESSING")) {
                    done++;
                } else {
                    db.sql("UPDATE withdrawals SET updated_at = ? WHERE id = ?").params(ts(clock.instant()), w.id()).update();
                }
            } catch (RuntimeException e) {
                log.info("Couldn't reconcile withdrawal {} yet: {}", w.id(), e.getMessage());
            }
        }
        return done;
    }

    public Withdrawal withdrawal(UUID id) {
        return db.sql(WITHDRAWAL_SQL + " WHERE id = ?").param(id).query(Payments::withdrawal).single();
    }

    public Withdrawal myWithdrawal(UUID user, UUID id) {
        return db.sql(WITHDRAWAL_SQL + " WHERE id = ? AND user_id = ?").params(id, user).query(Payments::withdrawal).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such withdrawal of yours."));
    }

    public List<Withdrawal> withdrawals(UUID user) {
        return db.sql(WITHDRAWAL_SQL + " WHERE user_id = ? ORDER BY created_at DESC LIMIT 100").param(user).query(Payments::withdrawal).list();
    }

    private Optional<Withdrawal> withdrawalByKey(UUID user, String key) {
        return db.sql(WITHDRAWAL_SQL + " WHERE user_id = ? AND idempotency_key = ?").params(user, key).query(Payments::withdrawal).optional();
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    private static final String DEPOSIT_SQL =
            "SELECT id, user_id, amount_paise, status, bank_request_id, failure_reason, created_at, updated_at FROM deposits";
    private static final String WITHDRAWAL_SQL =
            "SELECT id, user_id, amount_paise, payee_vpa, status, failure_reason, created_at, updated_at FROM withdrawals";

    private static Deposit deposit(ResultSet rs, int n) throws SQLException {
        return new Deposit(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getLong("amount_paise"),
                rs.getString("status"), rs.getObject("bank_request_id", UUID.class), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Withdrawal withdrawal(ResultSet rs, int n) throws SQLException {
        return new Withdrawal(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getLong("amount_paise"),
                rs.getString("payee_vpa"), rs.getString("status"), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
