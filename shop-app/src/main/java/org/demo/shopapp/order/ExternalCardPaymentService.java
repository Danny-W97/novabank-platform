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

    public ExternalCardPaymentService(OrderStateService orderStateService,
                                      StripeCheckoutClient stripeCheckoutClient,
                                      ShopPaymentProperties properties,
                                      BankAuthorizationClient bankClient) {
        this.orderStateService = orderStateService;
        this.stripeCheckoutClient = stripeCheckoutClient;
        this.properties = properties;
        this.bankClient = bankClient;
    }

    public OrderResponse createCheckout(long orderId, String customerId, String idempotencyKey,
                                        ExternalCardCheckoutRequest request) {
        var key = ShopInput.idempotencyKey(idempotencyKey);
        var prepared = orderStateService.preparePayment(orderId, customerId, key);
        if (prepared.status() == OrderStatus.PAID) {
            return OrderResponse.from(prepared).withReplayed(true);
        }
        if (!properties.isStripe()) {
            throw new IllegalStateException("Zewnetrzny operator kart nie jest wlaczony. Ustaw SHOP_PAYMENT_PROVIDER=stripe.");
        }
        var quote = bankClient.cardQuote(new BankCardQuoteRequest(prepared.total(), request.currency()));
        if (quote.amount().compareTo(request.amount()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Kurs platnosci wygasl. Pobierz nowe przeliczenie.");
        }
        var checkout = stripeCheckoutClient.create(prepared, request.currency(), request.amount(), key);
        orderStateService.attachExternalPayment(orderId, customerId, checkout.provider(),
                checkout.paymentId(), checkout.checkoutUrl());
        return OrderResponse.from(orderStateService.findByOrderNumber(prepared.orderNumber()))
                .withCheckoutUrl(checkout.checkoutUrl());
    }
}
