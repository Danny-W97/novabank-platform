package org.demo.shopapp.web;

import jakarta.validation.Valid;
import org.demo.shopapp.auth.ShopUserDetails;
import org.demo.shopapp.cart.CartResponse;
import org.demo.shopapp.cart.CartService;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankCardQuoteRequest;
import org.demo.shopapp.payment.BankCardQuoteResponse;
import org.demo.shopapp.config.ShopPaymentProperties;
import org.demo.shopapp.order.CardCheckoutSessionResponse;
import org.demo.shopapp.order.CardCheckoutSessionService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final CartService cartService;
    private final BankAuthorizationClient bankClient;
    private final ShopPaymentProperties paymentProperties;
    private final CardCheckoutSessionService cardCheckoutSessionService;

    public CheckoutController(CartService cartService, BankAuthorizationClient bankClient,
                              ShopPaymentProperties paymentProperties,
                              CardCheckoutSessionService cardCheckoutSessionService) {
        this.cartService = cartService;
        this.bankClient = bankClient;
        this.paymentProperties = paymentProperties;
        this.cardCheckoutSessionService = cardCheckoutSessionService;
    }

    @org.springframework.web.bind.annotation.GetMapping("/card-provider")
    CardProviderResponse cardProvider() {
        return new CardProviderResponse(paymentProperties.getProvider(), paymentProperties.isStripe());
    }

    @PostMapping("/quote")
    BankCardQuoteResponse quote(
            @AuthenticationPrincipal ShopUserDetails user,
            @Valid @RequestBody CheckoutQuoteRequest request
    ) {
        CartResponse cart = cartService.getCart(user.getCustomerId());
        if (cart.items().isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Koszyk jest pusty."
            );
        }
        if (!"PLN".equals(cart.currency())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "Kasa sklepu oczekuje cen w PLN."
            );
        }
        return bankClient.cardQuote(new BankCardQuoteRequest(cart.total(), normalizeCurrency(request.currency())));
    }

    @PostMapping("/card-session")
    CardCheckoutSessionResponse startCardSession(
            @AuthenticationPrincipal ShopUserDetails user,
            @Valid @RequestBody CardSessionRequest request,
            HttpSession session
    ) {
        return cardCheckoutSessionService.start(
                request.orderId(), user.getCustomerId(), normalizeCurrency(request.currency()), request.amount(), session
        );
    }

    @PostMapping("/card-session-from-cart")
    CardCheckoutSessionResponse startCardSessionFromCart(
            @AuthenticationPrincipal ShopUserDetails user,
            @Valid @RequestBody CardSessionFromCartRequest request,
            HttpSession session
    ) {
        return cardCheckoutSessionService.startFromCart(
                user.getCustomerId(), normalizeCurrency(request.currency()), request.amount(), session
        );
    }

    @org.springframework.web.bind.annotation.GetMapping("/card-session")
    CardCheckoutSessionResponse currentCardSession(HttpSession session) {
        return cardCheckoutSessionService.current(session);
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "PLN";
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    public record CheckoutQuoteRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z]{3}")
            String currency
    ) {
    }

    public record CardSessionFromCartRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z]{3}") String currency,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin("0.01")
            @jakarta.validation.constraints.Digits(integer = 17, fraction = 2) java.math.BigDecimal amount
    ) {
    }

    public record CardSessionRequest(
            @jakarta.validation.constraints.NotNull Long orderId,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z]{3}") String currency,
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin("0.01")
            @jakarta.validation.constraints.Digits(integer = 17, fraction = 2) java.math.BigDecimal amount
    ) {
    }

    public record CardProviderResponse(String provider, boolean hosted) {
    }
}
