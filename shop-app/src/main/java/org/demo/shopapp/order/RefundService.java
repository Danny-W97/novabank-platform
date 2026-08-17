package org.demo.shopapp.order;

import org.demo.shopapp.common.ShopInput;
import org.demo.shopapp.payment.BankRefundRequest;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RefundService {

    private final OrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final BankAuthorizationClient bankClient;

    public RefundService(
            OrderRepository orderRepository,
            RefundRepository refundRepository,
            BankAuthorizationClient bankClient
    ) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.bankClient = bankClient;
    }

    @Transactional
    public RefundResponse refund(
            long orderId,
            String customerId,
            String idempotencyKey,
            String reason
    ) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        var order = orderRepository.findByIdForUpdate(orderId, customerId);
        var normalizedReason = reason.strip();
        var existing = refundRepository.findByIdempotencyKeyForUpdate(key);
        if (existing.isPresent()) {
            validateSameRefund(existing.get(), order, normalizedReason);
            if (!"PENDING".equals(existing.get().status())) {
                return RefundResponse.from(existing.get(), true);
            }
        }

        if (existing.isEmpty() && order.status() != OrderStatus.PAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a paid order can be refunded.");
        }
        if (existing.isEmpty() && (order.customerAccountNumber() == null
                || !order.customerAccountNumber().matches("\\d{26}"))
                && (order.bankReference() == null || !order.bankReference().matches("[A-Z0-9-]{8,32}"))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Customer bank account is not available for this order."
            );
        }
        if (existing.isEmpty() && refundRepository.existsCompletedForOrder(order.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has already been refunded.");
        }

        if (existing.isEmpty()) {
            existing = java.util.Optional.of(refundRepository.create(
                    order.id(), order.orderNumber(), key, order.total(), order.currency(), normalizedReason
            ));
        }
        try {
            var bankResponse = bankClient.refund(new BankRefundRequest(
                    order.merchantAccountNumber(),
                    order.customerAccountNumber(),
                    order.orderNumber(),
                    order.total(),
                    order.currency(),
                    normalizedReason,
                    order.bankReference()
            ), key);
            var completed = refundRepository.markCompleted(key, bankResponse.referenceNumber());
            return RefundResponse.from(completed, false);
        } catch (RuntimeException exception) {
            if (isDefinitiveFailure(exception)) {
                var failed = refundRepository.markFailed(key, failureMessage(exception));
                return RefundResponse.from(failed, false);
            }
            var pending = refundRepository.markPending(key, failureMessage(exception));
            return RefundResponse.from(pending, false);
        }
    }

    private static boolean isDefinitiveFailure(RuntimeException exception) {
        return exception instanceof ResponseStatusException statusException
                && statusException.getStatusCode().is4xxClientError();
    }

    private static String failureMessage(RuntimeException exception) {
        if (exception instanceof ResponseStatusException statusException
                && statusException.getReason() != null
                && !statusException.getReason().isBlank()) {
            return statusException.getReason();
        }
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return "Nie można potwierdzić wyniku zwrotu. Ponów próbę z tym samym kluczem idempotencji.";
    }

    private static void validateSameRefund(
            RefundRepository.RefundRow existing,
            OrderRepository.OrderRow order,
            String reason
    ) {
        if (!existing.orderId().equals(order.id())
                || existing.amount().compareTo(order.total()) != 0
                || !existing.currency().equals(order.currency())
                || !existing.reason().equals(reason)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency-Key is already used for different refund data."
            );
        }
    }
}
