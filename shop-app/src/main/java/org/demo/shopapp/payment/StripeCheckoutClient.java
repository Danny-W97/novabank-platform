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
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        this.client = builder.clone().requestFactory(factory).baseUrl(properties.getApiBaseUrl()).build();
        this.properties = properties;
    }

    public ExternalCheckoutResponse create(OrderRepository.OrderRow order, String currency,
                                           java.math.BigDecimal amount, String idempotencyKey,
                                           String attemptId, String merchantId) {
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
        form.add("metadata[attemptId]", attemptId);
        form.add("metadata[merchantId]", merchantId);
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

    public java.util.Map<String,Object> status(String sessionId) {
        ensureConfigured();
        return client.get().uri("/v1/checkout/sessions/{id}",sessionId)
                .headers(headers->headers.setBasicAuth(properties.getApiKey(),""))
                .retrieve().body(new ParameterizedTypeReference<java.util.Map<String,Object>>(){});
    }

    public java.util.Map<String,Object> cancellationStatus(String sessionId,String requestId) {
        ensureConfigured();
        var session=expandedStatus(sessionId);
        if("open".equals(session.get("status"))){
            // Do not cancel a Checkout PaymentIntent directly. Expire the Checkout session.
            try {
                client.post().uri("/v1/checkout/sessions/{id}/expire",sessionId)
                        .headers(h->h.setBasicAuth(properties.getApiKey(),""))
                        .header("Idempotency-Key","cancel-"+requestId).retrieve().toBodilessEntity();
            } catch(org.springframework.web.client.RestClientResponseException changed) {
                if(changed.getStatusCode().value()!=400)throw changed;
                // Completion may have won the race. Re-read authoritative state; do not infer cancellation.
            }
            session=expandedStatus(sessionId);
        }
        return session;
    }
    private java.util.Map<String,Object> expandedStatus(String id){
        return client.get().uri(b->b.path("/v1/checkout/sessions/{id}").queryParam("expand[]","payment_intent").build(id))
                .headers(h->h.setBasicAuth(properties.getApiKey(),"")).retrieve()
                .body(new ParameterizedTypeReference<java.util.Map<String,Object>>(){});
    }

    private void ensureConfigured() {
        if (!properties.isStripe() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Zewnetrzny operator kart nie jest skonfigurowany.");
        }
    }
}
