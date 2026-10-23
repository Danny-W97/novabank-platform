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
    private final PaymentAttemptService attempts;

    public OrderPaymentService(OrderStateService orderStateService, BankAuthorizationClient bankAuthorizationClient,
                                PaymentAttemptService attempts) {
        this.orderStateService = orderStateService;
        this.bankAuthorizationClient = bankAuthorizationClient;
        this.attempts = attempts;
    }

    public OrderResponse pay(long orderId, String customerId, String idempotencyKey) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        var preparation = attempts.begin(orderId, customerId, key, "BANK", null, null);
        var prepared = preparation.order();
        if (prepared.status() == OrderStatus.PAID) {
            return OrderResponse.from(prepared).withReplayed(true);
        }

        var attempt = preparation.attempt();
        if (!preparation.dispatch())
            return OrderResponse.from(prepared).withAuthorizationUrl(attempt.authorizationUrl()).withReplayed(true);
        var state = attempt.correlation();
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
            if(bankResponse==null || !attempt.merchantId().equals(bankResponse.merchantId()) || !state.equals(bankResponse.state())
                    || bankResponse.authorizationId()==null || bankResponse.authorizationUrl()==null)
                throw new IllegalStateException("Invalid bank authorization response.");
            attempts.attachAuthorization(attempt, bankResponse.authorizationId(), bankResponse.authorizationUrl());
            var linked = attempts.order(attempt);
            return OrderResponse.from(linked).withAuthorizationUrl(bankResponse.authorizationUrl());
        } catch (RuntimeException exception) {
            attempts.unknown(attempt);
            return OrderResponse.from(attempts.order(attempt));
        }
    }


}
