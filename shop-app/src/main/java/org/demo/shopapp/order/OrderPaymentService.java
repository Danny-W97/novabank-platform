package org.demo.shopapp.order;

import org.demo.shopapp.common.ShopInput;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankAuthorizationRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class OrderPaymentService {

    private final OrderStateService orderStateService;
    private final BankAuthorizationClient bankAuthorizationClient;

    public OrderPaymentService(OrderStateService orderStateService, BankAuthorizationClient bankAuthorizationClient) {
        this.orderStateService = orderStateService;
        this.bankAuthorizationClient = bankAuthorizationClient;
    }

    public OrderResponse pay(long orderId, String customerId, String idempotencyKey) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        var prepared = orderStateService.preparePayment(orderId, customerId, key);
        if (prepared.status() == OrderStatus.PAID) {
            return OrderResponse.from(prepared).withReplayed(true);
        }

        var state = prepared.paymentState() == null
                ? UUID.randomUUID().toString()
                : prepared.paymentState();
        try {
            var bankResponse = bankAuthorizationClient.create(new BankAuthorizationRequest(
                    prepared.orderNumber(),
                    prepared.customerId(),
                    prepared.merchantAccountNumber(),
                    prepared.total(),
                    prepared.currency(),
                    "Shop order " + prepared.orderNumber(),
                    key,
                    state
            ));
            orderStateService.attachAuthorization(orderId, customerId, bankResponse.authorizationId(), state);
            var linked = orderStateService.findByAuthorizationId(bankResponse.authorizationId());
            return OrderResponse.from(linked).withAuthorizationUrl(bankResponse.authorizationUrl());
        } catch (RuntimeException exception) {
            var failed = orderStateService.markFailed(orderId, failureMessage(exception));
            return OrderResponse.from(failed);
        }
    }

    private static String failureMessage(RuntimeException exception) {
        var message = exception.getMessage();
        if (exception instanceof ResponseStatusException statusException) {
            message = statusException.getReason();
        }
        if (message == null || message.isBlank()) {
            message = "Bank payment failed.";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
