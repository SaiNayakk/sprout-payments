package app.sprout.payments.domain;

import app.sprout.payments.domain.Payments.Created;
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
 * AutoPay for round-ups: a mandate the customer grants their bank once, debits Sprout services make
 * under it, and the spends the bank shares while it is active.
 *
 * <p>The same rules as deposits keep money from being lost or made up: the bank's answer is the truth
 * (a mandate the customer approved is active whatever this service thought), every bank debit and
 * ledger entry carries the debit's id, and a debit whose outcome is unknown stays {@code PENDING} and
 * is asked again, never guessed.
 */
@Service
public class AutoPay {

    private static final Logger log = LoggerFactory.getLogger(AutoPay.class);
    static final String PURPOSE = "Round-ups into your Sprout goals";

    public record Mandate(UUID id, UUID userId, long maxAmount, UUID bankMandateId, String status, String failureReason,
                          Instant createdAt, Instant updatedAt) {}

    public record Debit(UUID id, UUID userId, long amount, String reference, String description, String status, String failureReason,
                        Instant createdAt) {}

    public record Spend(long seq, UUID id, UUID userId, long amount, String payeeName, Instant at) {}

    private final JdbcClient db;
    private final Clock clock;
    private final Upstreams up;

    public AutoPay(JdbcClient db, Clock clock, Upstreams up) {
        this.db = db;
        this.clock = clock;
        this.up = up;
    }

    // ── mandates ─────────────────────────────────────────────────────────────

    public Created<Mandate> setUp(UUID user, String key, long maxAmount) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send an Idempotency-Key header (8 to 100 characters, e.g. a UUID).");
        }
        if (maxAmount < Money.paise("100.00") || maxAmount > Money.paise("10000.00")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An AutoPay limit is between ₹100 and ₹10,000 a debit.");
        }
        Optional<Mandate> earlier = byKey(user, key);
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
            db.sql("INSERT INTO mandates (id, user_id, idempotency_key, max_amount_paise, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, 'AWAITING_APPROVAL', ?, ?)").params(id, user, key, maxAmount, ts(now), ts(now)).update();
        } catch (DuplicateKeyException e) {
            return new Created<>(byKey(user, key).orElseThrow(), false);
        }
        try {
            Reply r = up.requestMandate(vpa, maxAmount, PURPOSE, id.toString());
            if (r.ok()) {
                db.sql("UPDATE mandates SET bank_mandate_id = ?, updated_at = ? WHERE id = ?")
                        .params(UUID.fromString(r.body().path("id").asText()), ts(clock.instant()), id).update();
            } else {
                fail(id, "Sprout Bank refused the request (" + r.code() + ").");
            }
        } catch (Unreachable e) {
            // the bank may have it: if the customer approves it anyway, its event still makes it active
            fail(id, "Sprout Bank couldn't be reached. Try again.");
        }
        return new Created<>(mandate(id), true);
    }

    private void fail(UUID id, String reason) {
        db.sql("UPDATE mandates SET status = 'FAILED', failure_reason = ?, updated_at = ? WHERE id = ? AND status = 'AWAITING_APPROVAL'")
                .params(reason, ts(clock.instant()), id).update();
    }

    public Mandate mine(UUID user) {
        return db.sql(MANDATE_SQL + " WHERE user_id = ? ORDER BY created_at DESC LIMIT 1").param(user).query(AutoPay::mandate).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "You haven't set up AutoPay."));
    }

    /** A mandate or spend event from the bank, already authenticated and not seen before. */
    public void receive(String type, UUID bankMandateId, String reference, UUID spendId, long amount, String payeeName, Instant at) {
        UUID id;
        try {
            id = UUID.fromString(reference);
        } catch (IllegalArgumentException e) {
            log.warn("Mandate event for unknown reference {}; ignoring", reference);
            return;
        }
        Optional<Mandate> m = db.sql(MANDATE_SQL + " WHERE id = ?").param(id).query(AutoPay::mandate).optional();
        if (m.isEmpty()) {
            log.warn("Mandate event for a mandate we don't have ({}); ignoring", reference);
            return;
        }
        Instant now = clock.instant();
        switch (type) {
            case "MANDATE_ACTIVE" -> db.sql("UPDATE mandates SET status = 'ACTIVE', bank_mandate_id = ?, failure_reason = NULL, updated_at = ? "
                    + "WHERE id = ?").params(bankMandateId, ts(now), id).update();
            case "MANDATE_DECLINED", "MANDATE_EXPIRED", "MANDATE_REVOKED" -> db.sql("UPDATE mandates SET status = ?, updated_at = ? WHERE id = ?")
                    .params(type.substring("MANDATE_".length()), ts(now), id).update();
            case "SPEND" -> db.sql("INSERT INTO spends (id, user_id, amount_paise, payee_name, at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING")
                    .params(spendId, m.get().userId(), amount, payeeName, ts(at)).update();
            default -> log.warn("Unknown mandate event {} for {}", type, id);
        }
    }

    // ── debits ───────────────────────────────────────────────────────────────

    /** Debits under the customer's active mandate. Unique per reference; throws {@link Unreachable} if the outcome is unknown. */
    public Created<Debit> debit(UUID user, long amount, String reference, String description) {
        if (amount <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The amount must be above zero.");
        }
        Optional<Debit> earlier = debitByReference(reference);
        if (earlier.isPresent()) {
            Debit d = earlier.get();
            if (d.status().equals("PENDING")) {
                advance(d);
                d = debitByReference(reference).orElseThrow();
            }
            if (d.status().equals("PENDING")) {
                throw new Unreachable("the outcome of debit " + d.id() + " isn't known yet", null);
            }
            return new Created<>(d, false);
        }
        Optional<Mandate> active = db.sql(MANDATE_SQL + " WHERE user_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1")
                .param(user).query(AutoPay::mandate).optional();
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        String refusal = active.isEmpty() ? "No active AutoPay mandate."
                : amount > active.get().maxAmount() ? "Above the AutoPay limit of ₹" + Money.rupees(active.get().maxAmount()) + "." : null;
        try {
            db.sql("INSERT INTO mandate_debits (id, user_id, mandate_id, reference, amount_paise, description, status, failure_reason, "
                            + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, user, active.map(Mandate::id).orElse(null), reference, amount, description,
                            refusal == null ? "PENDING" : "FAILED", refusal, ts(now), ts(now))
                    .update();
        } catch (DuplicateKeyException e) {
            return debit(user, amount, reference, description);
        }
        if (refusal == null) {
            advance(debitByReference(reference).orElseThrow());
        }
        Debit d = debitByReference(reference).orElseThrow();
        if (d.status().equals("PENDING")) {
            throw new Unreachable("the outcome of debit " + id + " isn't known yet", null);
        }
        return new Created<>(d, true);
    }

    /** Asks the bank (again: the same reference) and books the money once it has moved. */
    private void advance(Debit d) {
        UUID bankMandate = db.sql("SELECT m.bank_mandate_id FROM mandate_debits d JOIN mandates m ON m.id = d.mandate_id WHERE d.id = ?")
                .param(d.id()).query(UUID.class).single();
        Reply r;
        try {
            r = up.debitMandate(bankMandate, d.amount(), d.id().toString());
        } catch (Unreachable e) {
            log.info("AutoPay debit {} not settled yet ({}); will retry", d.id(), e.getMessage());
            touch(d.id());
            return;
        }
        if (!r.ok()) {
            finish(d.id(), "FAILED", switch (r.code()) {
                case "INSUFFICIENT_BALANCE" -> "Not enough money in your bank account.";
                case "REQUEST_NOT_PENDING" -> "Your AutoPay mandate isn't active.";
                default -> "Sprout Bank refused the debit (" + r.code() + ").";
            });
            return;
        }
        try {
            Reply booked = up.post("autopay:" + d.id(), "AutoPay: " + d.description(), d.id().toString(),
                    List.of(new Leg("sprout:bank", "DEBIT", d.amount()), new Leg(Payments.cash(d.userId()), "CREDIT", d.amount())));
            if (booked.ok()) {
                finish(d.id(), "COMPLETED", null);
            } else {
                log.error("Ledger refused AutoPay debit {}: {}; will retry", d.id(), booked.code());
                touch(d.id());
            }
        } catch (Unreachable e) {
            log.info("AutoPay debit {} taken but not booked yet ({}); will retry", d.id(), e.getMessage());
            touch(d.id());
        }
    }

    /** Debits whose outcome wasn't known: ask again. Called on a schedule. */
    public int reconcile(Instant olderThan) {
        List<Debit> stuck = db.sql(DEBIT_SQL + " WHERE status = 'PENDING' AND updated_at < ? ORDER BY updated_at LIMIT 50")
                .param(ts(olderThan)).query(AutoPay::debit).list();
        int done = 0;
        for (Debit d : stuck) {
            advance(d);
            done += debitByReference(d.reference()).map(x -> x.status().equals("PENDING") ? 0 : 1).orElse(0);
        }
        return done;
    }

    private void finish(UUID id, String status, String reason) {
        db.sql("UPDATE mandate_debits SET status = ?, failure_reason = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'")
                .params(status, reason, ts(clock.instant()), id).update();
    }

    private void touch(UUID id) {
        db.sql("UPDATE mandate_debits SET updated_at = ? WHERE id = ?").params(ts(clock.instant()), id).update();
    }

    // ── spends ───────────────────────────────────────────────────────────────

    public List<Spend> spends(long after, int limit) {
        return db.sql("SELECT seq, id, user_id, amount_paise, payee_name, at FROM spends WHERE seq > ? ORDER BY seq LIMIT ?")
                .params(after, limit)
                .query((rs, n) -> new Spend(rs.getLong("seq"), rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getLong("amount_paise"), rs.getString("payee_name"), rs.getTimestamp("at").toInstant()))
                .list();
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    private Optional<Mandate> byKey(UUID user, String key) {
        return db.sql(MANDATE_SQL + " WHERE user_id = ? AND idempotency_key = ?").params(user, key).query(AutoPay::mandate).optional();
    }

    Mandate mandate(UUID id) {
        return db.sql(MANDATE_SQL + " WHERE id = ?").param(id).query(AutoPay::mandate).single();
    }

    private Optional<Debit> debitByReference(String reference) {
        return db.sql(DEBIT_SQL + " WHERE reference = ?").param(reference).query(AutoPay::debit).optional();
    }

    private static final String MANDATE_SQL =
            "SELECT id, user_id, max_amount_paise, bank_mandate_id, status, failure_reason, created_at, updated_at FROM mandates";
    private static final String DEBIT_SQL =
            "SELECT id, user_id, amount_paise, reference, description, status, failure_reason, created_at FROM mandate_debits";

    private static Mandate mandate(ResultSet rs, int n) throws SQLException {
        return new Mandate(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getLong("max_amount_paise"),
                rs.getObject("bank_mandate_id", UUID.class), rs.getString("status"), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static Debit debit(ResultSet rs, int n) throws SQLException {
        return new Debit(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getLong("amount_paise"),
                rs.getString("reference"), rs.getString("description"), rs.getString("status"), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
