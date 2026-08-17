package org.demo.bankdemo.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.regex.Pattern;

public class PaymentAuthorizationEntryPoint implements AuthenticationEntryPoint {

    private static final Pattern PAYMENT_PATH = Pattern.compile("^/shop/authorize/[A-Za-z0-9_-]+$");

    private final LoginUrlAuthenticationEntryPoint defaultEntryPoint =
            new LoginUrlAuthenticationEntryPoint("/login");

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException, ServletException {
        var path = request.getRequestURI().substring(request.getContextPath().length());
        if (PAYMENT_PATH.matcher(path).matches()) {
            var loginUrl = UriComponentsBuilder.fromPath("/login")
                    .queryParam("continue", path)
                    .build()
                    .encode()
                    .toUriString();
            response.sendRedirect(loginUrl);
            return;
        }
        defaultEntryPoint.commence(request, response, authenticationException);
    }
}
