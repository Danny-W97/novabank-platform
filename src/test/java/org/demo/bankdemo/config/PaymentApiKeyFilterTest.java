package org.demo.bankdemo.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentApiKeyFilterTest {
    @Test void purchaseCancellationRequiresAuthorizeScopeAndInstallsMerchantIdentity()throws Exception{
        for(String scope:java.util.List.of("payments:status","payments:authorize")){
            var properties=configuredProperties();properties.setKeyMerchants("synthetic=merchant");properties.setApiKeys("synthetic|synthetic-secret|"+scope+"|2099-12-31T23:59:59Z");
            var request=new MockHttpServletRequest("POST","/api/payment-authorizations/by-key/synthetic-key/cancel");
            request.addHeader("X-API-Key","synthetic-secret");request.addHeader("X-API-Key-Id","synthetic");
            var response=new MockHttpServletResponse();var chain=new MockFilterChain();new PaymentApiKeyFilter(properties).doFilter(request,response,chain);
            if(scope.equals("payments:authorize")){assertThat(response.getStatus()).isEqualTo(200);assertThat(request.getAttribute(org.demo.bankdemo.payment.MerchantIdentity.REQUEST_ATTRIBUTE)).isNotNull();}
            else{assertThat(response.getStatus()).isEqualTo(403);assertThat(chain.getRequest()).isNull();}
        }
    }

    @Test
    void acceptsRotatedKeyWithPaymentScope() throws ServletException, IOException {
        var properties = configuredProperties();
        properties.setApiKeys(
                "shop-old|old-secret|payments:refund|2099-12-31T23:59:59Z;"
                        + "shop-new|new-secret|payments:refund|2099-12-31T23:59:59Z"
        );
        var filter = new PaymentApiKeyFilter(properties);
        var request = request("new-secret", "shop-new");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void rejectsKeyWithoutRequiredScope() throws ServletException, IOException {
        var properties = configuredProperties();
        properties.setApiKeys("shop-read|read-secret|payments:read|2099-12-31T23:59:59Z");
        var filter = new PaymentApiKeyFilter(properties);
        var response = new MockHttpServletResponse();

        filter.doFilter(request("read-secret", "shop-read"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void requiresStatusScopeForAuthorizationStatusRead() throws ServletException, IOException {
        var properties = configuredProperties();
        properties.setApiKeys("shop-read|read-secret|payments:authorize|2099-12-31T23:59:59Z");
        var filter = new PaymentApiKeyFilter(properties);
        var response = new MockHttpServletResponse();

        filter.doFilter(statusRequest("read-secret", "shop-read"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void cardStatusRequiresCardScope() throws ServletException, IOException {
        var properties = configuredProperties();
        properties.setApiKeys("shop-read|read-secret|payments:status|2099-12-31T23:59:59Z");
        var filter = new PaymentApiKeyFilter(properties);
        var request = new MockHttpServletRequest("GET", "/api/card-payments/status/card-1");
        request.addHeader("X-API-Key", "read-secret");
        request.addHeader("X-API-Key-Id", "shop-read");

        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void limitsRequestsPerKeyAndClient() throws ServletException, IOException {
        var properties = configuredProperties();
        properties.setApiKey("one-secret");
        properties.setRateLimitPerMinute(1);
        var filter = new PaymentApiKeyFilter(properties);

        var firstResponse = new MockHttpServletResponse();
        filter.doFilter(request("one-secret", null), firstResponse, new MockFilterChain());
        var secondResponse = new MockHttpServletResponse();
        filter.doFilter(request("one-secret", null), secondResponse, new MockFilterChain());

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getHeader("Retry-After")).isEqualTo("60");
    }

    private static MockHttpServletRequest request(String secret, String keyId) {
        var request = new MockHttpServletRequest("POST", "/api/payments/refunds");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-API-Key", secret);
        if (keyId != null) {
            request.addHeader("X-API-Key-Id", keyId);
        }
        return request;
    }

    private static MockHttpServletRequest statusRequest(String secret, String keyId) {
        var request = new MockHttpServletRequest("GET", "/api/payment-authorizations/auth-1/status");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-API-Key", secret);
        request.addHeader("X-API-Key-Id", keyId);
        return request;
    }
    private static PaymentApiProperties configuredProperties() {
        var properties = new PaymentApiProperties();
        properties.setKeyMerchants("legacy=merchant;shop-old=merchant;shop-new=merchant;shop-read=merchant");
        properties.setMerchants("merchant=22222222222222222222222222");
        return properties;
    }

    @Test
    void missingMerchantBindingFailsClosed() throws Exception {
        var properties = new PaymentApiProperties();
        properties.setApiKey("synthetic-test-key");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new PaymentApiKeyFilter(properties).doFilter(request("synthetic-test-key", null), response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rotatedKeysKeepTheSameMerchantIdentity() {
        var properties = configuredProperties();
        assertThat(properties.merchantForKey("shop-old").id()).isEqualTo(properties.merchantForKey("shop-new").id());
    }

}
