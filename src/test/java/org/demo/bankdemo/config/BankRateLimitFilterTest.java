package org.demo.bankdemo.config;

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

class BankRateLimitFilterTest {

    @Test
    void rejectsLoginWhenIpLimitIsExceeded() throws ServletException, IOException {
        var service = mock(DistributedRateLimitService.class);
        var properties = new RateLimitProperties();
        when(service.tryAcquire(any(), any(), any(Integer.class), any()))
                .thenReturn(new DistributedRateLimitService.Decision(false, 42, true));
        var response = new MockHttpServletResponse();

        new BankRateLimitFilter(service, properties)
                .doFilter(loginRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
    }

    @Test
    void returnsServiceUnavailableWhenRedisIsDown() throws ServletException, IOException {
        var service = mock(DistributedRateLimitService.class);
        when(service.tryAcquire(any(), any(), any(Integer.class), any()))
                .thenReturn(new DistributedRateLimitService.Decision(false, 30, false));
        var response = new MockHttpServletResponse();

        new BankRateLimitFilter(service, new RateLimitProperties())
                .doFilter(loginRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
    }

    private static MockHttpServletRequest loginRequest() {
        var request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("127.0.0.1");
        request.addParameter("username", "user@example.pl");
        return request;
    }
}
