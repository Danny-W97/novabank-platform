package org.demo.shopapp.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopRateLimitFilterTest {

    @Test
    void rejectsRepeatedPaymentRequests() throws ServletException, IOException {
        var service = mock(ShopDistributedRateLimitService.class);
        when(service.tryAcquire(any(), any(), any(Integer.class), any()))
                .thenReturn(new ShopDistributedRateLimitService.Decision(false, 17, true));
        var response = new MockHttpServletResponse();

        new ShopRateLimitFilter(service, new ShopRateLimitProperties())
                .doFilter(paymentRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("17");
    }

    @Test
    void allowsNonProtectedRequests() throws ServletException, IOException {
        var service = mock(ShopDistributedRateLimitService.class);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        new ShopRateLimitFilter(service, new ShopRateLimitProperties())
                .doFilter(new MockHttpServletRequest("GET", "/novatech_shop.html"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest paymentRequest() {
        var request = new MockHttpServletRequest("POST", "/api/orders/1/card-pay");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
