package app.sprout.payments.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.payments} in payments.yml. */
@ConfigurationProperties("sprout.payments")
public record PaymentsProperties(
        String minimum,
        String maximum,
        Duration reconcileEvery,
        Duration reconcileAfter,
        String callbackUrl,
        String serviceKey,
        Accounts accounts,
        Ledger ledger,
        Bank bank) {

    public record Accounts(String url, String serviceKey) {}

    public record Ledger(String url) {}

    public record Bank(String url, String partnerKey, String webhookSecret) {}
}
