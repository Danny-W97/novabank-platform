package org.demo.shopapp.order;

import org.demo.shopapp.common.ShopInput;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankCardPaymentRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CardOrderPaymentService {

    private final OrderStateService orderStateService;
    private final BankAuthorizationClient bankClient;
    private final OrderCartCleanupService cartCleanupService;
    private final PaymentAttemptService attempts;

    public CardOrderPaymentService(
            OrderStateService orderStateService,
            BankAuthorizationClient bankClient,
            OrderCartCleanupService cartCleanupService,
            PaymentAttemptService attempts
    ) {
        this.orderStateService = orderStateService;
        this.bankClient = bankClient;
        this.cartCleanupService = cartCleanupService;
        this.attempts = attempts;
    }

    public OrderResponse pay(
            long orderId,
            String customerId,
            String idempotencyKey,
            CardOrderPaymentRequest request
    ) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        var preparation = attempts.begin(orderId, customerId, key, "CARD", request.amount(), request.currency());
        var prepared = preparation.order();
        if (prepared.status() == OrderStatus.PAID) {
            cleanupCart(prepared);
            return OrderResponse.from(prepared).withReplayed(true);
        }
        var attempt = preparation.attempt();
        if (!preparation.dispatch()) return OrderResponse.from(prepared).withReplayed(true);
        if (!"PLN".equals(prepared.currency())) {
            attempts.decline(attempt, "Zamówienie kartowe musi być rozliczane bazowo w PLN.");
            return OrderResponse.from(attempts.order(attempt));
        }
        try {
            var bankResponse = bankClient.payCard(
                    new BankCardPaymentRequest(
                            request.cardNumber(), request.cvv(), request.expiry(),
                            prepared.merchantAccountNumber(), prepared.orderNumber(),
                            request.currency(), prepared.total(), request.amount(),
                            "Shop order " + prepared.orderNumber()
                    ),
                    key
            );
            if (bankResponse==null || !attempt.merchantId().equals(bankResponse.merchantId()) || !prepared.orderNumber().equals(bankResponse.orderId())
                    || bankResponse.amountInPln()==null || prepared.total().compareTo(bankResponse.amountInPln())!=0
                    || bankResponse.amount()==null || attempt.chargedAmount().compareTo(bankResponse.amount())!=0
                    || !attempt.chargedCurrency().equals(bankResponse.currency()))
                throw new IllegalStateException("Bank card response does not match the attempt.");
            if ("DECLINED".equals(bankResponse.status())) {
                attempts.decline(attempt,
                        bankResponse.reason() == null ? "Płatność kartą została odrzucona." : bankResponse.reason());
                return OrderResponse.from(attempts.order(attempt));
            }
            if (!"COMPLETED".equals(bankResponse.status())) throw new IllegalStateException("Unknown bank card status.");
            var paid = attempts.succeed(attempt, bankResponse.referenceNumber(), bankResponse.sourceAccountNumber());
            cleanupCart(paid);
            return OrderResponse.from(paid);
        } catch (RuntimeException exception) {
            attempts.unknown(attempt);
            return OrderResponse.from(attempts.order(attempt));
        }
    }

    private void cleanupCart(OrderRepository.OrderRow order) {
        try {
            cartCleanupService.cleanupPaidOrder(order);
        } catch (RuntimeException ignored) {
            // Payment is already confirmed; cleanup can be retried later.
        }
    }


}
