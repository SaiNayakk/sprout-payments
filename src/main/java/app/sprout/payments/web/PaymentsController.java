package app.sprout.payments.web;

import app.sprout.payments.config.PaymentsProperties;
import app.sprout.payments.domain.ApiException;
import app.sprout.payments.domain.AutoPay;
import app.sprout.payments.domain.AutoPay.Debit;
import app.sprout.payments.domain.AutoPay.Mandate;
import app.sprout.payments.domain.ErrorCode;
import app.sprout.payments.domain.Money;
import app.sprout.payments.domain.Payments;
import app.sprout.payments.domain.Payments.Created;
import app.sprout.payments.domain.Payments.Deposit;
import app.sprout.payments.domain.Payments.Withdrawal;
import app.sprout.payments.domain.Upstreams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The payments API (payments-v1.yaml), plus the signed callback Sprout Bank calls. */
@RestController
public class PaymentsController {

    private static final Logger log = LoggerFactory.getLogger(PaymentsController.class);

    public record MoneyRequest(String amount) {}

    public record MandateRequest(String maxAmount) {}

    public record DebitRequest(String userId, String amount, String reference, String description) {}

    private final Payments payments;
    private final AutoPay autoPay;
    private final PaymentsProperties props;
    private final ObjectMapper json;

    public PaymentsController(Payments payments, AutoPay autoPay, PaymentsProperties props, ObjectMapper json) {
        this.payments = payments;
        this.autoPay = autoPay;
        this.props = props;
        this.json = json;
    }

    @GetMapping("/v1/balance")
    public Map<String, String> balance(@RequestHeader(value = "X-User-Id", required = false) String user) {
        long[] b = payments.balance(userId(user));
        return Map.of("available", Money.rupees(b[0]), "withdrawing", Money.rupees(b[1]));
    }

    @PostMapping("/v1/deposits")
    public ResponseEntity<Map<String, Object>> deposit(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @RequestBody MoneyRequest req) {
        Created<Deposit> d = payments.deposit(userId(user), key, req.amount());
        return ResponseEntity.status(d.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(d.value()));
    }

    @GetMapping("/v1/deposits")
    public Map<String, Object> deposits(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("deposits", payments.deposits(userId(user)).stream().map(PaymentsController::dto).toList());
    }

    @GetMapping("/v1/deposits/{id}")
    public Map<String, Object> deposit(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(payments.myDeposit(userId(user), id));
    }

    @PostMapping("/v1/withdrawals")
    public ResponseEntity<Map<String, Object>> withdraw(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                        @RequestBody MoneyRequest req) {
        Created<Withdrawal> w = payments.withdraw(userId(user), key, req.amount());
        return ResponseEntity.status(w.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(w.value()));
    }

    @GetMapping("/v1/withdrawals")
    public Map<String, Object> withdrawals(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("withdrawals", payments.withdrawals(userId(user)).stream().map(PaymentsController::dto).toList());
    }

    @GetMapping("/v1/withdrawals/{id}")
    public Map<String, Object> withdrawal(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(payments.myWithdrawal(userId(user), id));
    }

    // ── AutoPay ──────────────────────────────────────────────────────────────

    @PostMapping("/v1/mandates")
    public ResponseEntity<Map<String, Object>> setUpAutoPay(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                            @RequestBody MandateRequest req) {
        Created<Mandate> m = autoPay.setUp(userId(user), key, Money.paise(req.maxAmount()));
        return ResponseEntity.status(m.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(m.value()));
    }

    @GetMapping("/v1/mandates/me")
    public Map<String, Object> myAutoPay(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return dto(autoPay.mine(userId(user)));
    }

    /** For Sprout services: a debit under the customer's AutoPay. An unknown outcome is a 503; repeating it is safe. */
    @PostMapping("/internal/v1/mandate-debits")
    public ResponseEntity<Map<String, Object>> debit(@RequestHeader(value = "X-Service-Key", required = false) String key,
                                                     @RequestBody DebitRequest req) {
        service(key);
        if (req.userId() == null || req.reference() == null || req.reference().isBlank() || req.reference().length() > 120
                || req.description() == null || req.description().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send userId, amount, reference (up to 120 characters) and description.");
        }
        Created<Debit> d;
        try {
            d = autoPay.debit(UUID.fromString(req.userId()), Money.paise(req.amount()), req.reference(), req.description());
        } catch (Upstreams.Unreachable e) {
            throw Upstreams.unavailable();
        }
        return ResponseEntity.status(d.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(d.value()));
    }

    @GetMapping("/internal/v1/spends")
    public Map<String, Object> spends(@RequestHeader(value = "X-Service-Key", required = false) String key,
                                      @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "200") int limit) {
        service(key);
        if (after < 0 || limit < 1 || limit > 500) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "after is 0 or more; limit is 1 to 500.");
        }
        List<AutoPay.Spend> page = autoPay.spends(after, limit);
        return Map.of("spends", page.stream().map(s -> Map.of("seq", s.seq(), "id", s.id().toString(), "userId", s.userId().toString(),
                        "amount", Money.rupees(s.amount()), "payeeName", s.payeeName(), "at", s.at().toString())).toList(),
                "next", page.isEmpty() ? after : page.get(page.size() - 1).seq());
    }

    private void service(String key) {
        if (key == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), props.serviceKey().getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Only Sprout services can call this.");
        }
    }

    /** Sprout Bank's callback: verified, applied at most once, or refused with 503 so the bank retries. */
    @PostMapping("/internal/v1/bank-events")
    public ResponseEntity<Void> bankEvent(@RequestHeader(value = "X-Bank-Signature", required = false) String signature,
                                          @RequestBody(required = false) String body) throws Exception {
        if (body == null || signature == null || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                ("sha256=" + sign(props.bank().webhookSecret(), body)).getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.INVALID_SIGNATURE, "This doesn't carry Sprout Bank's signature.");
        }
        JsonNode e = json.readTree(body);
        String type = e.path("type").asText();
        if (type.startsWith("MANDATE_") || type.equals("SPEND")) {
            payments.once(UUID.fromString(e.path("eventId").asText()), () -> autoPay.receive(type,
                    UUID.fromString(e.path("mandateId").asText()), e.path("reference").asText(),
                    e.hasNonNull("spendId") ? UUID.fromString(e.path("spendId").asText()) : null,
                    e.hasNonNull("amount") ? Money.paise(e.path("amount").asText()) : 0, e.path("payeeName").asText(""),
                    Instant.parse(e.path("occurredAt").asText())));
            return ResponseEntity.noContent().build();
        }
        try {
            payments.receive(UUID.fromString(e.path("eventId").asText()), e.path("type").asText(),
                    UUID.fromString(e.path("requestId").asText()), e.path("reference").asText(), Money.paise(e.path("amount").asText()));
        } catch (Upstreams.Unreachable | IllegalStateException ex) {
            log.warn("Couldn't apply bank event {} now ({}); the bank will retry", e.path("eventId").asText(), ex.getMessage());
            throw Upstreams.unavailable();
        }
        return ResponseEntity.noContent().build();
    }

    static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in to continue.");
        }
    }

    static Map<String, Object> dto(Deposit d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id().toString());
        m.put("amount", Money.rupees(d.amount()));
        m.put("status", d.status());
        if (d.bankRequestId() != null) {
            m.put("bankRequestId", d.bankRequestId().toString());
        }
        if (d.failureReason() != null) {
            m.put("failureReason", d.failureReason());
        }
        m.put("createdAt", d.createdAt().toString());
        m.put("updatedAt", d.updatedAt().toString());
        return m;
    }

    static Map<String, Object> dto(Mandate m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id().toString());
        out.put("maxAmount", Money.rupees(m.maxAmount()));
        out.put("status", m.status());
        if (m.bankMandateId() != null) {
            out.put("bankMandateId", m.bankMandateId().toString());
        }
        if (m.failureReason() != null) {
            out.put("failureReason", m.failureReason());
        }
        out.put("createdAt", m.createdAt().toString());
        out.put("updatedAt", m.updatedAt().toString());
        return out;
    }

    static Map<String, Object> dto(Debit d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.id().toString());
        out.put("userId", d.userId().toString());
        out.put("amount", Money.rupees(d.amount()));
        out.put("reference", d.reference());
        out.put("status", d.status());
        if (d.failureReason() != null) {
            out.put("failureReason", d.failureReason());
        }
        out.put("createdAt", d.createdAt().toString());
        return out;
    }

    static Map<String, Object> dto(Withdrawal w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id().toString());
        m.put("amount", Money.rupees(w.amount()));
        m.put("status", w.status());
        if (w.failureReason() != null) {
            m.put("failureReason", w.failureReason());
        }
        m.put("createdAt", w.createdAt().toString());
        m.put("updatedAt", w.updatedAt().toString());
        return m;
    }
}
