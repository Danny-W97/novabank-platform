package org.demo.shopapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class ShopSecurityConfig {

    private static final String[] OPENAPI_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    @Bean
    SecurityFilterChain shopSecurityFilterChain(
            HttpSecurity http,
            Environment environment,
            BasicAuthenticationEntryPoint actuatorBasicAuthenticationEntryPoint,
            ShopRateLimitFilter shopRateLimitFilter,
            MetricsBasicAuthenticationFilter metricsBasicAuthenticationFilter
    ) throws Exception {
        var csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieName("SHOP-XSRF-TOKEN");
        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/payments/stripe/webhook")
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(OPENAPI_PATHS)
                        .access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        !environment.acceptsProfiles(Profiles.of("prod"))
                                                || authentication.get().getAuthorities().stream()
                                                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))
                                ))
                        .requestMatchers("/api/auth/me").access(clientRoles())
                        .requestMatchers(
                                "/",
                                "/novatech_shop.html",
                                "/product.html",
                                "/shop-login.html",
                                "/shop-register.html",
                                "/card-provider.html",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/api/auth/**",
                                "/api/products/**"
                        ).permitAll()
                        .requestMatchers(
                                "/shop-profile.html",
                                "/shop-address.html",
                                "/shop-orders.html",
                                "/checkout.html",
                                "/api/account/**"
                        ).access(clientRoles())
                        .requestMatchers("/api/cart/**", "/api/orders/**", "/api/checkout/**").access(clientRoles())
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN", "METRICS")
                        .requestMatchers("/actuator/**").access(clientRoles("ADMIN"))
                        .requestMatchers("/payment/callback", "/api/payments/stripe/webhook",
                                "/api/payments/stripe/success", "/api/payments/stripe/cancel",
                                "/error", "/favicon.ico", "/favicon.svg").permitAll()
                        .anyRequest().denyAll()
                )
                .formLogin(form -> form
                        .loginPage("/shop-login.html")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/novatech_shop.html", true)
                        .failureUrl("/shop-login.html?error")
                        .permitAll()
                )
                .httpBasic(Customizer.withDefaults())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/novatech_shop.html?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("SHOP_SESSION")
                        .permitAll()
                )
                .headers(headers -> {
                    if (environment.acceptsProfiles(Profiles.of("prod"))) {
                        headers.httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000)
                        );
                    }
                })
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                actuatorBasicAuthenticationEntryPoint,
                                request -> request.getRequestURI()
                                        .substring(request.getContextPath().length())
                                        .startsWith("/actuator")
                        )
                )
                .addFilterBefore(shopRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(metricsBasicAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                )
                .build();
    }

    private static org.springframework.security.authorization.AuthorizationManager<org.springframework.security.web.access.intercept.RequestAuthorizationContext>
    clientRoles(String... roles) {
        return (supplier, context) -> {
            var authentication = supplier.get();
            boolean active = authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.UserDetails user
                    && user.isEnabled() && user.isAccountNonLocked() && user.isAccountNonExpired() && user.isCredentialsNonExpired();
            var allowed=roles.length==0?new String[]{"CUSTOMER","ADMIN"}:roles;
            boolean role = authentication.getAuthorities().stream().anyMatch(authority ->
                    java.util.Arrays.stream(allowed).anyMatch(expected -> authority.getAuthority().equals("ROLE_"+expected)));
            return new org.springframework.security.authorization.AuthorizationDecision(authentication.isAuthenticated() && active && role);
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    BasicAuthenticationEntryPoint actuatorBasicAuthenticationEntryPoint() {
        var entryPoint = new BasicAuthenticationEntryPoint();
        entryPoint.setRealmName("NovaTech Shop Actuator");
        return entryPoint;
    }
}
