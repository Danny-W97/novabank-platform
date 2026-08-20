package org.demo.shopapp.order;

import org.demo.shopapp.config.ShopOrderProperties;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankAuthorizationStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    private final OrderRepository orderRepository;
    private final OrderStateService orderStateService;
    private final BankAuthorizationClient bankClient;
    private final OrderCartCleanupService cartCleanupService;
    private final ShopOrderProperties properties;

    public PaymentReconciliationJob(
            OrderRepository orderRepository,
            OrderStateService orderStateService,
            BankAuthorizationClient bankClient,
            OrderCartCleanupService cartCleanupService,
            ShopOrderProperties properties
    ) {
        this.orderRepository = orderRepository;
        this.orderStateService = orderStateService;
        this.bankClient = bankClient;
        this.cartCleanupService = cartCleanupService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${shop.orders.reconciliation-interval-ms:30000}",
            initialDelayString = "${shop.orders.reconciliation-initial-delay-ms:10000}"
    )
    public void reconcilePendingPayments() {
        if (!properties.isReconciliationEnabled()) {
            return;
        }

        var staleBefore = Instant.now().minusMillis(properties.getReconciliationIntervalMs());
        var candidates = orderRepository.findReconciliationCandidates(
                properties.getReconciliationBatchSize(), staleBefore
        );
        candidates.forEach(this::reconcile);
    }

    private void reconcile(OrderRepository.OrderRow order) {
        try {
            var bankStatus = bankClient.status(order.bankAuthorizationId());
            if (!matchesOrder(order, bankStatus)) {
                log.warn("Payment reconciliation skipped for order {}: bank data does not match order.",
                        order.orderNumber());
                return;
            }

            switch (bankStatus.status()) {
                case "PENDING" -> reconcilePending(order);
                case "COMPLETED", "REDEEMED" -> reconcilePaid(order, bankStatus);
                case "FAILED" -> reconcileFailed(order, bankStatus);
                default -> log.warn("Unknown bank payment status {} for order {}.",
                        bankStatus.status(), order.orderNumber());
            }
        } catch (RuntimeException exception) {
            log.warn("Payment reconciliation failed for order {}: {}",
                    order.orderNumber(), exception.getMessage());
        }
    }

    private void reconcilePending(OrderRepository.OrderRow order) {
        if (order.status() == OrderStatus.FAILED) {
            orderStateService.markPending(order.id(), "Bank authorization is still pending.");
        }
        orderStateService.markPaymentReconciled(order.id());
    }

    private void reconcilePaid(
            OrderRepository.OrderRow order,
            BankAuthorizationStatusResponse bankStatus
    ) {
        if (bankStatus.customerAccountNumber() == null
                || !bankStatus.customerAccountNumber().matches("\\d{26}")) {
            log.warn("Payment reconciliation skipped for order {}: missing customer account.",
                    order.orderNumber());
            return;
        }
        var paid = orderStateService.markPaid(
                order.id(),
                order.paymentIdempotencyKey(),
                bankStatus.referenceNumber(),
                bankStatus.customerAccountNumber()
        );
        try {
            cartCleanupService.cleanupPaidOrder(paid);
        } catch (RuntimeException exception) {
            log.warn("Payment {} was reconciled, but cart cleanup failed.", order.orderNumber());
        }
        orderStateService.markPaymentReconciled(order.id());
    }

    private void reconcileFailed(
            OrderRepository.OrderRow order,
            BankAuthorizationStatusResponse bankStatus
    ) {
        if (order.status() != OrderStatus.PAID) {
            orderStateService.markFailed(order.id(), failureReason(bankStatus));
        }
        orderStateService.markPaymentReconciled(order.id());
    }

    private static boolean matchesOrder(
            OrderRepository.OrderRow order,
            BankAuthorizationStatusResponse bankStatus
    ) {
        return order.orderNumber().equals(bankStatus.shopOrderId())
                && order.total().compareTo(bankStatus.amount()) == 0
                && order.currency().equals(bankStatus.currency());
    }

    private static String failureReason(BankAuthorizationStatusResponse bankStatus) {
        if (bankStatus.failureReason() != null && !bankStatus.failureReason().isBlank()) {
            return bankStatus.failureReason().length() <= 500
                    ? bankStatus.failureReason()
                    : bankStatus.failureReason().substring(0, 500);
        }
        return "Bank payment failed.";
    }
}
