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
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public RefundService(
            OrderRepository orderRepository,
            RefundRepository refundRepository,
            BankAuthorizationClient bankClient,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.bankClient = bankClient;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public RefundResponse refund(
            long orderId,
            String customerId,
            String idempotencyKey,
            String reason
    ) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        var prepared = transactions.execute(status -> prepare(orderId, customerId, key, reason));
        if (!"PENDING".equals(prepared.refund().status())) return RefundResponse.from(prepared.refund(), true);
        var order = prepared.order();
        try {
            var bankResponse = bankClient.refund(new BankRefundRequest(
                    order.merchantAccountNumber(), order.customerAccountNumber(), order.orderNumber(),
                    order.total(), order.currency(), prepared.refund().reason(), order.bankReference()), key);
            if (bankResponse == null || !("refund:" + order.orderNumber()).equals(bankResponse.orderId())
                    || bankResponse.amount() == null || order.total().compareTo(bankResponse.amount()) != 0
                    || !order.currency().equals(bankResponse.currency()) || !"COMPLETED".equals(bankResponse.status())
                    || bankResponse.referenceNumber() == null)
                throw new IllegalStateException("Refund response does not match the reserved operation.");
            var completed = transactions.execute(status -> {
                orderRepository.findByIdForUpdate(orderId, customerId);
                refundRepository.findByIdempotencyKeyForUpdate(key).orElseThrow();
                return refundRepository.markCompleted(key, bankResponse.referenceNumber());
            });
            return RefundResponse.from(completed, false);
        } catch (RuntimeException exception) {
            var pending = transactions.execute(status -> refundRepository.markPending(key,
                    "Wynik zwrotu niepotwierdzony. Ponów wyłącznie z tym samym kluczem."));
            return RefundResponse.from(pending, false);
        }
    }

    private PreparedRefund prepare(long orderId, String customerId, String key, String reason) {
        var order = orderRepository.findByIdForUpdate(orderId, customerId);
        if ("STRIPE".equalsIgnoreCase(order.paymentProvider()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stripe refund requires the Stripe-specific procedure.");
        var normalizedReason = reason.strip();
        var existing = refundRepository.findByIdempotencyKeyForUpdate(key);
        if (existing.isPresent()) {
            validateSameRefund(existing.get(), order, normalizedReason);
            if (!"PENDING".equals(existing.get().status())) {
                return new PreparedRefund(order, existing.get());
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
        if (existing.isEmpty() && refundRepository.existsUnresolvedOrCompletedForOrder(order.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order already has a pending or completed refund; reuse its key.");
        }

        if (existing.isEmpty()) {
            existing = java.util.Optional.of(refundRepository.create(
                    order.id(), order.orderNumber(), key, order.total(), order.currency(), normalizedReason
            ));
        }
        return new PreparedRefund(order, existing.orElseThrow());
    }

    private record PreparedRefund(OrderRepository.OrderRow order, RefundRepository.RefundRow refund) { }





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
