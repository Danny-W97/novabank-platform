package org.demo.shopapp.order;

import org.demo.shopapp.common.ShopInput;
import org.demo.shopapp.config.ShopPaymentProperties;
import org.demo.shopapp.payment.StripeCheckoutClient;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankCardQuoteRequest;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExternalCardPaymentService {

    private final OrderStateService orderStateService;
    private final StripeCheckoutClient stripeCheckoutClient;
    private final ShopPaymentProperties properties;
    private final BankAuthorizationClient bankClient;
    private final PaymentAttemptService attempts;

    public ExternalCardPaymentService(OrderStateService orderStateService,
                                      StripeCheckoutClient stripeCheckoutClient,
                                      ShopPaymentProperties properties,
                                      BankAuthorizationClient bankClient, PaymentAttemptService attempts) {
        this.orderStateService = orderStateService;
        this.stripeCheckoutClient = stripeCheckoutClient;
        this.properties = properties;
        this.bankClient = bankClient;
        this.attempts = attempts;
    }

    public OrderResponse createCheckout(long orderId, String customerId, String idempotencyKey,
                                        ExternalCardCheckoutRequest request) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        if (!properties.isStripe() || properties.getApiKey()==null || properties.getApiKey().isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe TEST is not configured.");
        var preparation = attempts.begin(orderId, customerId, key, "STRIPE", request.amount(), request.currency());
        var prepared = preparation.order();
        if (prepared.status() == OrderStatus.PAID) {
            return OrderResponse.from(prepared).withReplayed(true);
        }
        var attempt = preparation.attempt();
        if (!preparation.dispatch()) return OrderResponse.from(prepared).withCheckoutUrl(attempt.checkoutUrl()).withReplayed(true);
        boolean financialRequestStarted = false;
        try {
        if (!properties.isStripe()) {
            throw new IllegalStateException("Zewnetrzny operator kart nie jest wlaczony. Ustaw SHOP_PAYMENT_PROVIDER=stripe.");
        }
        if (!"PLN".equals(prepared.currency()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Card checkout requires a PLN base order.");
        var quote = bankClient.cardQuote(new BankCardQuoteRequest(prepared.total(), request.currency()));
        if (quote.amount().compareTo(request.amount()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Kurs platnosci wygasl. Pobierz nowe przeliczenie.");
        }
        financialRequestStarted = true;
        var checkout = stripeCheckoutClient.create(prepared, request.currency(), request.amount(), key,
                attempt.id(), attempt.merchantId());
        attempts.attachExternal(attempt, checkout.paymentId(), checkout.checkoutUrl());
        return OrderResponse.from(attempts.order(attempt))
                .withCheckoutUrl(checkout.checkoutUrl());
        } catch (RuntimeException exception) {
            if (financialRequestStarted) attempts.unknown(attempt);
            else attempts.decline(attempt,"Wycena płatności nie została zaakceptowana; nie wysłano żądania obciążenia.");
            return OrderResponse.from(attempts.order(attempt));
        }
    }
}
