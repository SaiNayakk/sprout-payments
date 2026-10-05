package app.sprout.payments.config;

import app.sprout.payments.domain.Payments;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration(proxyBeanMethods = false)
public class PaymentsBeans {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Settles payments whose outcome is still unknown: lost callbacks, unanswered payouts. */
    @Component
    static class Reconciler {

        private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

        private final Payments payments;
        private final Clock clock;
        private final PaymentsProperties props;

        Reconciler(Payments payments, Clock clock, PaymentsProperties props) {
            this.payments = payments;
            this.clock = clock;
            this.props = props;
        }

        @Scheduled(fixedDelayString = "${sprout.payments.reconcile-every:15s}")
        void reconcile() {
            try {
                var before = clock.instant().minus(props.reconcileAfter());
                int deposits = payments.reconcileDeposits(before);
                int withdrawals = payments.reconcileWithdrawals(before);
                if (deposits + withdrawals > 0) {
                    log.info("Reconciled {} deposit(s) and {} withdrawal(s)", deposits, withdrawals);
                }
            } catch (RuntimeException e) {
                log.warn("Reconciliation didn't run this time: {}", e.getMessage());
            }
        }
    }
}
