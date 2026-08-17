package org.demo.bankdemo.config;

import org.demo.bankdemo.auth.LoginFailureHandler;
import org.demo.bankdemo.auth.LoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;

@Configuration
public class SecurityConfig {

    private static final String[] OPENAPI_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            LoginSuccessHandler loginSuccessHandler,
            LoginFailureHandler loginFailureHandler,
            BankRateLimitFilter bankRateLimitFilter,
            PaymentApiKeyFilter paymentApiKeyFilter,
            RequestCache requestCache,
            SecurityContextRepository securityContextRepository,
            PaymentAuthorizationEntryPoint paymentAuthorizationEntryPoint,
            BasicAuthenticationEntryPoint actuatorBasicAuthenticationEntryPoint,
            MetricsBasicAuthenticationFilter metricsBasicAuthenticationFilter,
            Environment environment
    ) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(OPENAPI_PATHS)
                        .access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        !environment.acceptsProfiles(Profiles.of("prod"))
                                                || authentication.get().getAuthorities().stream()
                                                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))
                                ))
                        .requestMatchers(
                                "/",
                                "/login",
                                "/register",
                                "/about",
                                "/terms",
                                "/login/2fa",
                                "/verify-email/**",
                                "/password/forgot",
                                "/password/reset",
                                "/error",
                                "/favicon.ico",
                                "/favicon.svg",
                                "/css/**",
                        "/js/**",
                                "/images/**"
                ).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN", "METRICS")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(
                        HttpMethod.POST,
                                "/api/payments",
                                "/api/payments/refunds",
                                "/api/card-payments",
                                "/api/card-payments/quote",
                                "/api/payment-authorizations",
                                "/api/payment-authorizations/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/payment-authorizations/*/status").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(loginSuccessHandler)
                        .failureHandler(loginFailureHandler)
                        .permitAll()
                )
                .httpBasic(Customizer.withDefaults())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("BANK_SESSION")
                        .permitAll()
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                        .invalidSessionUrl("/login?expired")
                )
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .requestCache(cache -> cache.requestCache(requestCache))
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                actuatorBasicAuthenticationEntryPoint,
                                request -> request.getRequestURI()
                                        .substring(request.getContextPath().length())
                                        .startsWith("/actuator")
                        )
                        .defaultAuthenticationEntryPointFor(
                                paymentAuthorizationEntryPoint,
                                request -> request.getRequestURI()
                                        .substring(request.getContextPath().length())
                                        .startsWith("/shop/authorize/")
                        )
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                request -> {
                                    var path = request.getRequestURI()
                                            .substring(request.getContextPath().length());
                                    return !path.startsWith("/api/") && !path.startsWith("/actuator/");
                                }
                        )
                )
                .headers(headers -> {
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                            "default-src 'self'; "
                                    + "style-src 'self' https://cdn.jsdelivr.net; "
                                    + "font-src 'self' https://cdn.jsdelivr.net; "
                                    + "script-src 'self' https://cdn.jsdelivr.net; "
                                    + "img-src 'self' data:; "
                                    + "connect-src 'self'; "
                                    + "object-src 'none'; "
                                    + "base-uri 'self'; "
                                    + "frame-ancestors 'none'; "
                                    + "form-action 'self'"
                    ));
                    headers.referrerPolicy(referrer -> referrer.policy(
                            ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN
                    ));
                    headers.permissionsPolicy(permissions -> permissions.policy(
                            "camera=(), microphone=(), geolocation=()"
                    ));
                    if (environment.acceptsProfiles(Profiles.of("prod"))) {
                        headers.httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)
                        );
                    }
                })
                .addFilterBefore(paymentApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(bankRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(metricsBasicAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    RequestCache requestCache() {
        return new HttpSessionRequestCache();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository()
        );
    }

    @Bean
    PaymentAuthorizationEntryPoint paymentAuthorizationEntryPoint() {
        return new PaymentAuthorizationEntryPoint();
    }

    @Bean
    BasicAuthenticationEntryPoint actuatorBasicAuthenticationEntryPoint() {
        var entryPoint = new BasicAuthenticationEntryPoint();
        entryPoint.setRealmName("NovaBank Actuator");
        return entryPoint;
    }
}
