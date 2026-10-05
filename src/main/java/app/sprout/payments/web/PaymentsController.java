package app.sprout.payments.web;

import app.sprout.payments.config.PaymentsProperties;
import app.sprout.payments.domain.ApiException;
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
import org.springframework.web.bind.annotation.RestController;

/** The payments API (payments-v1.yaml), plus the signed callback Sprout Bank calls. */
@RestController
public class PaymentsController {

    private static final Logger log = LoggerFactory.getLogger(PaymentsController.class);

    public record MoneyRequest(String amount) {}

    private final Payments payments;
    private final PaymentsProperties props;
    private final ObjectMapper json;

    public PaymentsController(Payments payments, PaymentsProperties props, ObjectMapper json) {
        this.payments = payments;
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

    /** Sprout Bank's callback: verified, applied at most once, or refused with 503 so the bank retries. */
    @PostMapping("/internal/v1/bank-events")
    public ResponseEntity<Void> bankEvent(@RequestHeader(value = "X-Bank-Signature", required = false) String signature,
                                          @RequestBody(required = false) String body) throws Exception {
        if (body == null || signature == null || !MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                ("sha256=" + sign(props.bank().webhookSecret(), body)).getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.INVALID_SIGNATURE, "This doesn't carry Sprout Bank's signature.");
        }
        JsonNode e = json.readTree(body);
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
