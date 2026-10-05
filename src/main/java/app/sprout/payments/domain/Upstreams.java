package app.sprout.payments.domain;

import app.sprout.payments.config.PaymentsProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The services payments talks to: accounts (whose money and where it goes), the ledger (the money)
 * and Sprout Bank (the outside world). Plain HTTP with short timeouts; an unreachable service is
 * reported as {@link Unreachable} so callers decide what "unknown" means for them.
 */
@Component
public class Upstreams {

    /** No answer, or an answer that isn't a decision (5xx). The outcome is unknown. */
    public static class Unreachable extends RuntimeException {
        public Unreachable(String what, Throwable cause) {
            super(what, cause);
        }
    }

    /** A definite answer from the service. */
    public record Reply(int status, JsonNode body) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public String code() {
            return body == null ? "" : body.path("code").asText();
        }
    }

    public record Account(String bankVpa) {}

    private final PaymentsProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Onward onward;

    public Upstreams(PaymentsProperties props, ObjectMapper json, Onward onward) {
        this.onward = onward;
        this.props = props;
        this.json = json;
    }

    // ── accounts ─────────────────────────────────────────────────────────────

    public Account account(UUID userId) {
        Reply r = send("accounts", HttpRequest.newBuilder(URI.create(props.accounts().url() + "/internal/v1/accounts/" + userId))
                .header("X-Service-Key", props.accounts().serviceKey()).GET());
        if (r.status() == 404) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
        }
        if (!r.ok()) {
            throw unavailable();
        }
        return new Account(r.body().path("bankVpa").asText());
    }

    // ── ledger ───────────────────────────────────────────────────────────────

    public record Leg(String account, String side, long paise) {}

    /** Posts an entry; 201/200 are both success (200 = posted before with this key). */
    public Reply post(String key, String description, String reference, List<Leg> legs) {
        Map<String, Object> body = Map.of("idempotencyKey", key, "description", description, "reference", reference,
                "postings", legs.stream().map(l -> Map.of("account", l.account(), "side", l.side(), "amount", Money.rupees(l.paise()))).toList());
        return send("ledger", HttpRequest.newBuilder(URI.create(props.ledger().url() + "/v1/journal-entries"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    public long balance(String account) {
        Reply r = send("ledger", HttpRequest.newBuilder(URI.create(props.ledger().url() + "/v1/accounts/"
                + URLEncoder.encode(account, StandardCharsets.UTF_8))).GET());
        if (!r.ok()) {
            throw unavailable();
        }
        return Money.paise(r.body().path("balance").asText());
    }

    // ── bank ─────────────────────────────────────────────────────────────────

    public Reply collect(String payerVpa, long paise, String reference) {
        Map<String, Object> body = Map.of("payerVpa", payerVpa, "amount", Money.rupees(paise), "reference", reference,
                "note", "Add money to Sprout", "callbackUrl", props.callbackUrl());
        return send("bank", HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/collect-requests"))
                .header("Content-Type", "application/json").header("X-Partner-Key", props.bank().partnerKey())
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    public Reply collectStatus(UUID requestId) {
        return send("bank", HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/collect-requests/" + requestId))
                .header("X-Partner-Key", props.bank().partnerKey()).GET());
    }

    public Reply payout(String payeeVpa, long paise, String reference) {
        Map<String, Object> body = Map.of("payeeVpa", payeeVpa, "amount", Money.rupees(paise), "reference", reference);
        return send("bank", HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/payouts"))
                .header("Content-Type", "application/json").header("X-Partner-Key", props.bank().partnerKey())
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private Reply send(String what, HttpRequest.Builder req) {
        onward.headers(req);
        try {
            HttpResponse<String> res = http.send(req.timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 500) {
                throw new Unreachable(what + " answered " + res.statusCode(), null);
            }
            JsonNode body = res.body() == null || res.body().isBlank() ? null : json.readTree(res.body());
            return new Reply(res.statusCode(), body);
        } catch (Unreachable e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new Unreachable(what + " unreachable: " + e.getClass().getSimpleName(), e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ApiException unavailable() {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Part of Sprout isn't reachable right now. Nothing was moved; try again shortly.",
                5, Map.of());
    }
}
