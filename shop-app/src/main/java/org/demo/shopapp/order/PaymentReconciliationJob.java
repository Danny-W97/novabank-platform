package org.demo.shopapp.order;

import org.demo.shopapp.config.ShopOrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class PaymentReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);
    private final PaymentAttemptRepository attempts;
    private final PaymentAttemptService states;
    private final PaymentResolutionService resolution;
    private final ShopOrderProperties properties;

    public PaymentReconciliationJob(PaymentAttemptRepository attempts, PaymentAttemptService states,
                                    PaymentResolutionService resolution, ShopOrderProperties properties) {
        this.attempts = attempts;
        this.states = states;
        this.resolution = resolution;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${shop.orders.reconciliation-interval-ms:30000}",
            initialDelayString = "${shop.orders.reconciliation-initial-delay-ms:10000}")
    public void reconcilePendingPayments() {
        if (!properties.isReconciliationEnabled()) return;
        try {
            for (var attempt : attempts.candidates(Instant.now().minusMillis(properties.getReconciliationIntervalMs()),
                    properties.getReconciliationBatchSize())) {
                try {
                    resolution.refresh(attempt);
                } catch (RuntimeException exception) {
                    try {
                        states.unknown(attempt);
                        int count = states.failure(attempt);
                        if (count >= 3) {
                            log.error("RECONCILIATION_SUSPENDED: attempt {}, order ID {}, provider {}. "
                                    + "3 failed checks; outcome UNKNOWN; new payment remains blocked.",
                                    attempt.id(), attempt.orderId(), attempt.provider());
                        } else {
                            log.warn("Payment check failed: attempt {}, order ID {}, provider {}, check {}/3.",
                                    attempt.id(), attempt.orderId(), attempt.provider(), count);
                        }
                    } catch (RuntimeException saveException) {
                        log.error("Payment reconciliation failure could not be saved; attempt {}. New payment remains blocked.",
                                attempt.id());
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.error("Payment reconciliation candidate query failed.");
        }
    }
}
