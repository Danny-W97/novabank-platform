package org.demo.shopapp.payment;

import org.demo.shopapp.config.ShopBankProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BankAuthorizationClient {

    private final RestClient bankRestClient;
    private final ShopBankProperties properties;

    public BankAuthorizationClient(RestClient bankRestClient, ShopBankProperties properties) {
        this.bankRestClient = bankRestClient;
        this.properties = properties;
    }

    public BankAuthorizationResponse create(BankAuthorizationRequest request) {
        ensureConfigured();
        try {
            var spec = bankRestClient.post()
                    .uri("/api/payment-authorizations")
                    .header("X-API-Key", properties.getApiKey())
                    .body(request);
            addKeyId(spec);
            return spec.retrieve().body(BankAuthorizationResponse.class);
        } catch (RestClientResponseException exception) {
            throw rejected(exception);
        }
    }

    public BankAuthorizationRedeemResponse redeem(String authorizationId, BankAuthorizationRedeemRequest request) {
        ensureConfigured();
        try {
            var spec = bankRestClient.post()
                    .uri("/api/payment-authorizations/{id}/redeem", authorizationId)
                    .header("X-API-Key", properties.getApiKey())
                    .body(request);
            addKeyId(spec);
            return spec.retrieve().body(BankAuthorizationRedeemResponse.class);
        } catch (RestClientResponseException exception) {
            throw rejected(exception);
        }
    }

    public BankAuthorizationStatusResponse status(String authorizationId) {
        ensureConfigured();
        try {
            var spec = bankRestClient.get()
                    .uri("/api/payment-authorizations/{id}/status", authorizationId)
                    .header("X-API-Key", properties.getApiKey());
            addKeyId(spec);
            return spec.retrieve().body(BankAuthorizationStatusResponse.class);
        } catch (RestClientResponseException exception) {
            throw rejected(exception);
        }
    }

    public BankAuthorizationStatusResponse statusByKey(String key) {
        ensureConfigured();
        try {
            var spec=bankRestClient.get().uri("/api/payment-authorizations/by-key/{key}/status",key)
                    .header("X-API-Key",properties.getApiKey());
            addKeyId(spec);
            return spec.retrieve().body(BankAuthorizationStatusResponse.class);
        } catch (RestClientResponseException exception) { throw rejected(exception); }
    }

    public String authorizationUrl(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid bank authorization ID.");
        return properties.getBaseUrl().replaceAll("/+$", "") + "/shop/authorize/" + id;
    }

    public BankAuthorizationStatusResponse cancelByKey(String key) {
        ensureConfigured();
        var spec=bankRestClient.post().uri("/api/payment-authorizations/by-key/{key}/cancel",key)
                .header("X-API-Key",properties.getApiKey());
        addKeyId(spec);
        return spec.retrieve().body(BankAuthorizationStatusResponse.class);
    }

    public BankAuthorizationRedeemResponse refund(BankRefundRequest request, String idempotencyKey) {
        ensureConfigured();
        try {
            var spec = bankRestClient.post()
                    .uri("/api/payments/refunds")
                    .header("X-API-Key", properties.getApiKey())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request);
            addKeyId(spec);
            return spec.retrieve().body(BankAuthorizationRedeemResponse.class);
        } catch (RestClientResponseException exception) {
            throw rejected(exception);
        }
    }

    public BankCardQuoteResponse cardQuote(BankCardQuoteRequest request) {
        ensureConfigured();
        try {
            var spec = bankRestClient.post()
                    .uri("/api/card-payments/quote")
                    .header("X-API-Key", properties.getApiKey())
                    .body(request);
            addKeyId(spec);
            return spec.retrieve().body(BankCardQuoteResponse.class);
        } catch (RestClientResponseException exception) {
            throw rejected(exception);
        }
    }

    public BankCardPaymentResponse payCard(BankCardPaymentRequest request, String idempotencyKey) {
        ensureConfigured();
        try {
            var spec = bankRestClient.post()
                    .uri("/api/card-payments")
                    .header("X-API-Key", properties.getApiKey())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request);
            addKeyId(spec);
            return spec.retrieve().body(BankCardPaymentResponse.class);
        } catch (RestClientResponseException exception) {
            throw rejected(exception);
        }
    }

    public BankCardPaymentResponse cardStatus(String idempotencyKey,
                                              String merchantAccountNumber, String orderId) {
        ensureConfigured();
        try {
            var spec = bankRestClient.get()
                    .uri(builder -> builder.path("/api/card-payments/status/{key}")
                            .queryParam("merchantAccountNumber", merchantAccountNumber)
                            .queryParam("orderId", orderId)
                            .build(idempotencyKey))
                    .header("X-API-Key", properties.getApiKey());
            addKeyId(spec);
            return spec.retrieve().body(BankCardPaymentResponse.class);
        } catch (RestClientResponseException exception) {
            throw rejected(exception);
        }
    }

    private void addKeyId(RestClient.RequestHeadersSpec<?> spec) {
        if (properties.getApiKeyId() != null && !properties.getApiKeyId().isBlank()) {
            spec.header("X-API-Key-Id", properties.getApiKeyId());
        }
    }

    private void ensureConfigured() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Bank API key is not configured.");
        }
    }

    private static ResponseStatusException rejected(RestClientResponseException exception) {
        return new ResponseStatusException(exception.getStatusCode(), "Bank authorization was rejected.", exception);
    }
}
