package org.demo.shopapp.order;

import org.demo.shopapp.common.ShopInput;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankCardPaymentRequest;
import org.springframework.stereotype.Service;

@Service
public class CardOrderPaymentService {

    private final OrderStateService orderStateService;
    private final BankAuthorizationClient bankClient;
    private final OrderCartCleanupService cartCleanupService;

    public CardOrderPaymentService(
            OrderStateService orderStateService,
            BankAuthorizationClient bankClient,
            OrderCartCleanupService cartCleanupService
    ) {
        this.orderStateService = orderStateService;
        this.bankClient = bankClient;
        this.cartCleanupService = cartCleanupService;
    }

    public OrderResponse pay(
            long orderId,
            String customerId,
            String idempotencyKey,
            CardOrderPaymentRequest request
    ) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        var prepared = orderStateService.preparePayment(orderId, customerId, key);
        if (prepared.status() == OrderStatus.PAID) {
            cleanupCart(prepared);
            return OrderResponse.from(prepared).withReplayed(true);
        }
        if (!"PLN".equals(prepared.currency())) {
            var failed = orderStateService.markFailed(orderId, "Zamówienie musi być rozliczane bazowo w PLN.");
            return OrderResponse.from(failed);
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
            if (!"COMPLETED".equals(bankResponse.status())) {
                var failed = orderStateService.markFailed(orderId,
                        bankResponse.reason() == null ? "Płatność kartą została odrzucona." : bankResponse.reason());
                return OrderResponse.from(failed);
            }
            var paid = orderStateService.markPaid(
                    orderId, key, bankResponse.referenceNumber(), bankResponse.sourceAccountNumber()
            );
            cleanupCart(paid);
            return OrderResponse.from(paid);
        } catch (RuntimeException exception) {
            var failed = orderStateService.markFailed(orderId, failureMessage(exception));
            return OrderResponse.from(failed);
        }
    }

    private void cleanupCart(OrderRepository.OrderRow order) {
        try {
            cartCleanupService.cleanupPaidOrder(order);
        } catch (RuntimeException ignored) {
            // Payment is already confirmed; cleanup can be retried later.
        }
    }

    private static String failureMessage(RuntimeException exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Nie udało się wykonać płatności kartą.";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
