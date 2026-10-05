package app.sprout.payments;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.payments.domain.Payments;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Payments on a real Postgres, against stand-ins for accounts, the ledger and Sprout Bank that can
 * be told to refuse or to go away, so every path money can take is exercised.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=payments", "sprout.payments.reconcile-every=1h"})
@AutoConfigureMockMvc
class PaymentsApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SECRET = "dev-only-webhook-secret";

    // the stand-ins' state
    static final Map<String, String> VPAS = new ConcurrentHashMap<>();           // userId -> vpa
    static final Map<String, Long> BALANCES = new ConcurrentHashMap<>();         // ledger account -> paise
    static final Map<String, String> ENTRIES = new ConcurrentHashMap<>();        // idempotency key -> body
    static final AtomicBoolean LEDGER_DOWN = new AtomicBoolean();
    static final AtomicBoolean BANK_DOWN = new AtomicBoolean();
    static final AtomicInteger PAYOUT_STATUS = new AtomicInteger(201);
    static final AtomicReference<String> COLLECT_STATUS = new AtomicReference<>("PENDING");
    static final Map<String, String> COLLECTS = new ConcurrentHashMap<>();       // request id -> amount
    static final HttpServer UPSTREAMS = upstreams();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + UPSTREAMS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=payments");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.payments.accounts.url", () -> base);
        r.add("sprout.payments.ledger.url", () -> base);
        r.add("sprout.payments.bank.url", () -> base);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-05T04:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.PAYMENTS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired MutableClock clock;
    @Autowired Payments payments;

    UUID user;

    @BeforeEach
    void customer() {
        user = UUID.randomUUID();
        VPAS.put(user.toString(), "asha" + user.toString().substring(0, 6) + "@sproutbank");
        LEDGER_DOWN.set(false);
        BANK_DOWN.set(false);
        PAYOUT_STATUS.set(201);
        COLLECT_STATUS.set("PENDING");
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    ResultActions send(String path, String key, String amount) throws Exception {
        var req = post(path).header("X-User-Id", user.toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"" + amount + "\"}");
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    ResultActions event(String type, JsonNode deposit, String eventId) throws Exception {
        String body = JSON.writeValueAsString(Map.of("eventId", eventId, "type", type,
                "requestId", deposit.path("bankRequestId").asText(UUID.randomUUID().toString()),
                "reference", deposit.path("id").asText(), "amount", deposit.path("amount").asText(),
                "occurredAt", "2026-10-05T04:00:00Z"));
        return mvc.perform(post("/internal/v1/bank-events").contentType(MediaType.APPLICATION_JSON)
                .header("X-Bank-Signature", "sha256=" + sign(body)).content(body));
    }

    JsonNode deposit(String amount) throws Exception {
        return body(send("/v1/deposits", UUID.randomUUID().toString(), amount).andExpect(status().isCreated()));
    }

    String depositStatus(JsonNode d) throws Exception {
        return body(mvc.perform(get("/v1/deposits/" + d.path("id").asText()).header("X-User-Id", user.toString()))).path("status").asText();
    }

    String available() throws Exception {
        return body(mvc.perform(get("/v1/balance").header("X-User-Id", user.toString()))).path("available").asText();
    }

    /** Adds money the whole way: deposit, then the bank's approval. */
    void funded(String amount) throws Exception {
        event("COLLECT_APPROVED", deposit(amount), UUID.randomUUID().toString()).andExpect(status().isNoContent());
    }

    // ── adding money ─────────────────────────────────────────────────────────

    @Test
    void addingMoneyWaitsForTheBankThenCreditsOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode d = body(send("/v1/deposits", key, "500.25").andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL")));
        assertThat(d.path("bankRequestId").asText()).isNotBlank();
        send("/v1/deposits", key, "500.25").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(d.path("id").asText()));
        assertThat(available()).isEqualTo("0.00");

        String eventId = UUID.randomUUID().toString();
        event("COLLECT_APPROVED", d, eventId).andExpect(status().isNoContent());
        event("COLLECT_APPROVED", d, eventId).andExpect(status().isNoContent());   // the bank retried
        assertThat(depositStatus(d)).isEqualTo("COMPLETED");
        assertThat(available()).isEqualTo("500.25");
        mvc.perform(get("/v1/balance").header("X-User-Id", user.toString())).andExpect(MATCHES_CONTRACT);
        mvc.perform(get("/v1/deposits").header("X-User-Id", user.toString())).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
    }

    @Test
    void forgedCallbacksAreRefused() throws Exception {
        JsonNode d = deposit("100");
        mvc.perform(post("/internal/v1/bank-events").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Bank-Signature", "sha256=" + "0".repeat(64)).content("{\"type\":\"COLLECT_APPROVED\"}"))
                .andExpect(status().isUnauthorized()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        assertThat(depositStatus(d)).isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    void declinedAndExpiredDepositsEndThatWay() throws Exception {
        JsonNode declined = deposit("10");
        event("COLLECT_DECLINED", declined, UUID.randomUUID().toString()).andExpect(status().isNoContent());
        assertThat(depositStatus(declined)).isEqualTo("DECLINED");
        JsonNode expired = deposit("10");
        event("COLLECT_EXPIRED", expired, UUID.randomUUID().toString()).andExpect(status().isNoContent());
        assertThat(depositStatus(expired)).isEqualTo("EXPIRED");
        assertThat(available()).isEqualTo("0.00");
    }

    @Test
    void anApprovalArrivingWhileTheLedgerIsDownIsRetriedNotLost() throws Exception {
        JsonNode d = deposit("250");
        String eventId = UUID.randomUUID().toString();
        LEDGER_DOWN.set(true);
        event("COLLECT_APPROVED", d, eventId).andExpect(status().isServiceUnavailable()).andExpect(MATCHES_CONTRACT);
        assertThat(depositStatus(d)).isEqualTo("AWAITING_APPROVAL");
        LEDGER_DOWN.set(false);
        event("COLLECT_APPROVED", d, eventId).andExpect(status().isNoContent());   // the bank's retry
        assertThat(depositStatus(d)).isEqualTo("COMPLETED");
        assertThat(available()).isEqualTo("250.00");
    }

    @Test
    void aLostCallbackIsFoundByAskingTheBank() throws Exception {
        JsonNode d = deposit("75");
        COLLECT_STATUS.set("APPROVED");
        clock.advance(Duration.ofMinutes(1));
        payments.reconcileDeposits(clock.instant().minusSeconds(10));
        assertThat(depositStatus(d)).isEqualTo("COMPLETED");
        assertThat(available()).isEqualTo("75.00");
    }

    @Test
    void ifTheBankWasUnreachableAnApprovalStillCredits() throws Exception {
        BANK_DOWN.set(true);
        JsonNode d = body(send("/v1/deposits", UUID.randomUUID().toString(), "40").andExpect(status().isCreated()));
        assertThat(d.path("status").asText()).isEqualTo("FAILED");
        event("COLLECT_APPROVED", d, UUID.randomUUID().toString()).andExpect(status().isNoContent());
        assertThat(depositStatus(d)).isEqualTo("COMPLETED");
        assertThat(available()).isEqualTo("40.00");
    }

    // ── withdrawing ──────────────────────────────────────────────────────────

    @Test
    void youCantWithdrawMoreThanYouHave() throws Exception {
        send("/v1/withdrawals", UUID.randomUUID().toString(), "1").andExpect(status().isUnprocessableEntity()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        funded("100");
        send("/v1/withdrawals", UUID.randomUUID().toString(), "100.01").andExpect(status().isUnprocessableEntity());
        assertThat(available()).isEqualTo("100.00");
    }

    @Test
    void aWithdrawalHoldsPaysThenSettles() throws Exception {
        funded("300");
        String key = UUID.randomUUID().toString();
        JsonNode w = body(send("/v1/withdrawals", key, "120.50").andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.status").value("COMPLETED")));
        send("/v1/withdrawals", key, "120.50").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(w.path("id").asText()));
        assertThat(available()).isEqualTo("179.50");
        assertThat(BALANCES.getOrDefault("customer:" + user + ":withdrawal-hold", 0L)).isZero();
        mvc.perform(get("/v1/withdrawals").header("X-User-Id", user.toString())).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
    }

    @Test
    void aPayoutTheBankRefusesGivesTheMoneyBack() throws Exception {
        funded("80");
        PAYOUT_STATUS.set(404);
        send("/v1/withdrawals", UUID.randomUUID().toString(), "50").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("back in your balance")));
        assertThat(available()).isEqualTo("80.00");
    }

    @Test
    void anUnansweredPayoutStaysProcessingUntilTheBankAnswers() throws Exception {
        funded("90");
        BANK_DOWN.set(true);
        JsonNode w = body(send("/v1/withdrawals", UUID.randomUUID().toString(), "60").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROCESSING")));
        assertThat(available()).isEqualTo("30.00");   // held, not lost
        BANK_DOWN.set(false);
        clock.advance(Duration.ofMinutes(1));
        payments.reconcileWithdrawals(clock.instant().minusSeconds(10));
        mvc.perform(get("/v1/withdrawals/" + w.path("id").asText()).header("X-User-Id", user.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(available()).isEqualTo("30.00");
    }

    // ── the edges ────────────────────────────────────────────────────────────

    @Test
    void requestsAreValidated() throws Exception {
        send("/v1/deposits", UUID.randomUUID().toString(), "0.50").andExpect(status().isBadRequest()).andExpect(MATCHES_CONTRACT);
        send("/v1/deposits", UUID.randomUUID().toString(), "100000.01").andExpect(status().isBadRequest());
        send("/v1/deposits", UUID.randomUUID().toString(), "12.345").andExpect(status().isBadRequest());
        send("/v1/deposits", null, "10").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        mvc.perform(get("/v1/balance")).andExpect(status().isUnauthorized());
        VPAS.remove(user.toString());
        send("/v1/deposits", UUID.randomUUID().toString(), "10").andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT)
                .andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
    }

    @Test
    void someoneElsesPaymentsAreInvisible() throws Exception {
        JsonNode d = deposit("10");
        mvc.perform(get("/v1/deposits/" + d.path("id").asText()).header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── stand-ins ────────────────────────────────────────────────────────────

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    static long paise(String rupees) {
        return Math.round(Double.parseDouble(rupees) * 100);
    }

    static String rupees(long p) {
        return String.format("%d.%02d", p / 100, p % 100);
    }

    /** Accounts, the ledger and Sprout Bank, each as small as payments needs. */
    static HttpServer upstreams() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/accounts/", ex -> {
                String user = ex.getRequestURI().getPath().substring("/internal/v1/accounts/".length());
                String vpa = VPAS.get(user);
                reply(ex, vpa == null ? 404 : 200, vpa == null ? Map.of("code", "NO_ACCOUNT") : Map.of("bankVpa", vpa));
            });
            s.createContext("/v1/journal-entries", ex -> {
                if (LEDGER_DOWN.get()) {
                    reply(ex, 503, Map.of("code", "UPSTREAM_UNAVAILABLE"));
                    return;
                }
                String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode e = JSON.readTree(raw);
                String key = e.path("idempotencyKey").asText();
                synchronized (BALANCES) {
                    if (ENTRIES.containsKey(key)) {
                        reply(ex, 200, Map.of("id", key));
                        return;
                    }
                    Map<String, Long> next = new java.util.HashMap<>();
                    for (JsonNode p : e.path("postings")) {
                        String account = p.path("account").asText();
                        boolean asset = account.equals("sprout:bank");
                        boolean debit = p.path("side").asText().equals("DEBIT");
                        long delta = (asset == debit ? 1 : -1) * paise(p.path("amount").asText());
                        next.merge(account, BALANCES.getOrDefault(account, 0L) + delta, (a, b) -> a + delta);
                    }
                    for (var n : next.entrySet()) {
                        if (n.getValue() < 0 && !n.getKey().equals("sprout:bank")) {
                            reply(ex, 422, Map.of("code", "INSUFFICIENT_FUNDS"));
                            return;
                        }
                    }
                    BALANCES.putAll(next);
                    ENTRIES.put(key, raw);
                }
                reply(ex, 201, Map.of("id", key));
            });
            s.createContext("/v1/accounts/", ex -> {
                if (LEDGER_DOWN.get()) {
                    reply(ex, 503, null);
                    return;
                }
                String account = java.net.URLDecoder.decode(ex.getRequestURI().getRawPath().substring("/v1/accounts/".length()),
                        StandardCharsets.UTF_8);
                reply(ex, 200, Map.of("account", account, "balance", rupees(BALANCES.getOrDefault(account, 0L))));
            });
            s.createContext("/partner/v1/collect-requests", ex -> {
                if (BANK_DOWN.get()) {
                    reply(ex, 503, null);
                    return;
                }
                if (ex.getRequestMethod().equals("POST")) {
                    JsonNode req = JSON.readTree(ex.getRequestBody());
                    String id = UUID.randomUUID().toString();
                    COLLECTS.put(id, req.path("amount").asText());
                    reply(ex, 201, Map.of("id", id, "status", "PENDING", "amount", req.path("amount").asText()));
                } else {
                    String id = ex.getRequestURI().getPath().substring("/partner/v1/collect-requests/".length());
                    reply(ex, 200, Map.of("id", id, "status", COLLECT_STATUS.get(), "amount", COLLECTS.getOrDefault(id, "0")));
                }
            });
            s.createContext("/partner/v1/payouts", ex -> {
                if (BANK_DOWN.get()) {
                    reply(ex, 503, null);
                    return;
                }
                int st = PAYOUT_STATUS.get();
                reply(ex, st, st / 100 == 2 ? Map.of("status", "COMPLETED") : Map.of("code", "NOT_FOUND"));
            });
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
