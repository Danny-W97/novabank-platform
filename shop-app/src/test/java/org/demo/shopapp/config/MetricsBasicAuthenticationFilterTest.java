package org.demo.shopapp.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsBasicAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsTechnicalMetricsCredentials() throws ServletException, IOException {
        var properties = new MetricsAccessProperties();
        properties.setUsername("metrics-reader");
        properties.setPassword("secret");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        new MetricsBasicAuthenticationFilter(properties).doFilter(
                request("metrics-reader", "secret"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_METRICS");
    }

    @Test
    void rejectsInvalidTechnicalMetricsCredentials() throws ServletException, IOException {
        var properties = new MetricsAccessProperties();
        properties.setUsername("metrics-reader");
        properties.setPassword("secret");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        new MetricsBasicAuthenticationFilter(properties).doFilter(
                request("metrics-reader", "wrong"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"metrics\"");
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest request(String username, String password) {
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        var credentials = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Basic " + credentials);
        return request;
    }
}
