package org.demo.shopapp.payment;

import org.demo.shopapp.config.ShopPaymentProperties;
import org.demo.shopapp.order.OrderRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.math.RoundingMode;

@Service
public class StripeCheckoutClient {

    private final RestClient client;
    private final ShopPaymentProperties properties;

    public StripeCheckoutClient(RestClient.Builder builder, ShopPaymentProperties properties) {
        this.client = builder.baseUrl(properties.getApiBaseUrl()).build();
        this.properties = properties;
    }

    public ExternalCheckoutResponse create(OrderRepository.OrderRow order, String currency,
                                           java.math.BigDecimal amount, String idempotencyKey) {
        ensureConfigured();
        var form = new LinkedMultiValueMap<String, String>();
        form.add("mode", "payment");
        form.add("payment_method_types[0]", "card");
        form.add("line_items[0][price_data][currency]", currency.toLowerCase());
        form.add("line_items[0][price_data][product_data][name]", "NovaTech Shop - " + order.orderNumber());
        form.add("line_items[0][price_data][unit_amount]", amount
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString());
        form.add("line_items[0][quantity]", "1");
        form.add("client_reference_id", order.orderNumber());
        form.add("metadata[orderNumber]", order.orderNumber());
        form.add("metadata[orderId]", order.id().toString());
        form.add("metadata[chargedCurrency]", currency.toUpperCase());
        form.add("metadata[chargedAmount]", amount.setScale(2).toPlainString());
        form.add("success_url", properties.getSuccessUrl().replace("{ORDER_NUMBER}", order.orderNumber()));
        form.add("cancel_url", properties.getCancelUrl().replace("{ORDER_NUMBER}", order.orderNumber()));
        try {
            var response = client.post()
                    .uri("/v1/checkout/sessions")
                    .headers(headers -> {
                        headers.setBasicAuth(properties.getApiKey(), "");
                        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                    })
                    .header("Idempotency-Key", idempotencyKey)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<java.util.Map<String, Object>>() { });
            var paymentId = response == null ? null : String.valueOf(response.get("id"));
            var checkoutUrl = response == null ? null : String.valueOf(response.get("url"));
            if (paymentId == null || "null".equals(paymentId) || paymentId.isBlank()
                    || checkoutUrl == null || "null".equals(checkoutUrl) || checkoutUrl.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Operator nie zwrocil adresu platnosci.");
            }
            return new ExternalCheckoutResponse("stripe", paymentId, checkoutUrl);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Operator platnosci odrzucil zadanie.", exception);
        }
    }

    private void ensureConfigured() {
        if (!properties.isStripe() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Zewnetrzny operator kart nie jest skonfigurowany.");
        }
    }
}
