package org.demo.bankdemo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;

@Component
public class MetricsBasicAuthenticationFilter extends OncePerRequestFilter {

    private static final String PROMETHEUS_PATH = "/actuator/prometheus";
    private final MetricsAccessProperties properties;

    public MetricsBasicAuthenticationFilter(MetricsAccessProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!PROMETHEUS_PATH.equals(request.getRequestURI()) || !properties.isConfigured()) {
            filterChain.doFilter(request, response);
            return;
        }

        var credentials = basicCredentials(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (credentials == null || !matches(credentials[0], credentials[1])) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"metrics\"");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Metrics authentication is required.");
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(
                properties.getUsername(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_METRICS"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private boolean matches(String username, String password) {
        return constantTimeEquals(username, properties.getUsername())
                && constantTimeEquals(password, properties.getPassword());
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String[] basicCredentials(String header) {
        if (header == null || !header.startsWith("Basic ")) {
            return null;
        }
        try {
            var decoded = new String(
                    Base64.getDecoder().decode(header.substring(6)),
                    StandardCharsets.UTF_8
            );
            var separator = decoded.indexOf(':');
            return separator < 0
                    ? null
                    : new String[]{decoded.substring(0, separator), decoded.substring(separator + 1)};
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
