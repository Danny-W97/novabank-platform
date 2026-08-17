package org.demo.shopapp.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderStateService {

    private final OrderRepository orderRepository;

    public OrderStateService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderRepository.OrderRow preparePayment(
            long orderId,
            String customerId,
            String idempotencyKey
    ) {
        return orderRepository.preparePayment(orderId, customerId, idempotencyKey);
    }

    @Transactional
    public void attachAuthorization(long orderId, String customerId, String authorizationId, String state) {
        orderRepository.attachAuthorization(orderId, customerId, authorizationId, state);
    }

    @Transactional
    public void attachExternalPayment(long orderId, String customerId, String provider,
                                      String paymentId, String checkoutUrl) {
        orderRepository.attachExternalPayment(orderId, customerId, provider, paymentId, checkoutUrl);
    }

    @Transactional(readOnly = true)
    public OrderRepository.OrderRow findByAuthorizationId(String authorizationId) {
        return orderRepository.findByAuthorizationId(authorizationId);
    }

    @Transactional(readOnly = true)
    public OrderRepository.OrderRow findByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber);
    }

    @Transactional(readOnly = true)
    public OrderRepository.OrderRow findByExternalPaymentId(String externalPaymentId) {
        return orderRepository.findByExternalPaymentId(externalPaymentId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderRepository.OrderRow markPaid(
            long orderId,
            String idempotencyKey,
            String bankReference,
            String customerAccountNumber
    ) {
        return orderRepository.markPaid(orderId, idempotencyKey, bankReference, customerAccountNumber);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderRepository.OrderRow markPaidExternal(long orderId, String paymentId) {
        return orderRepository.markPaidExternal(orderId, paymentId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderRepository.OrderRow markFailed(long orderId, String reason) {
        return orderRepository.markFailed(orderId, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderRepository.OrderRow markPending(long orderId, String reason) {
        return orderRepository.markPending(orderId, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPaymentReconciled(long orderId) {
        orderRepository.markPaymentReconciled(orderId);
    }
}
