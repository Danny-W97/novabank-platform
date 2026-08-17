package org.demo.shopapp.order;

import jakarta.servlet.http.HttpSession;
import org.demo.shopapp.cart.CartService;
import org.demo.shopapp.common.ShopInput;
import org.demo.shopapp.config.ShopPaymentProperties;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankCardQuoteRequest;
import org.demo.shopapp.payment.BankCardQuoteResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class CardCheckoutSessionService {

    private static final String ORDER_ID = "SHOP_CARD_ORDER_ID";
    private static final String ORDER_NUMBER = "SHOP_CARD_ORDER_NUMBER";
    private static final String AMOUNT = "SHOP_CARD_AMOUNT";
    private static final String CURRENCY = "SHOP_CARD_CURRENCY";
    private static final String IDEMPOTENCY_KEY = "SHOP_CARD_IDEMPOTENCY_KEY";
    private static final String EXPIRES_AT = "SHOP_CARD_EXPIRES_AT";
    private static final Duration SESSION_TTL = Duration.ofMinutes(10);

    private final OrderService orderService;
    private final CartService cartService;
    private final BankAuthorizationClient bankClient;
    private final ShopPaymentProperties paymentProperties;

    public CardCheckoutSessionService(
            OrderService orderService,
            CartService cartService,
            BankAuthorizationClient bankClient,
            ShopPaymentProperties paymentProperties
    ) {
        this.orderService = orderService;
        this.cartService = cartService;
        this.bankClient = bankClient;
        this.paymentProperties = paymentProperties;
    }

    public CardCheckoutSessionResponse start(
            long orderId,
            String customerId,
            String currency,
            BigDecimal amount,
            HttpSession session
    ) {
        if (!"bank-demo".equalsIgnoreCase(paymentProperties.getProvider())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Wewnętrzny formularz jest dostępny tylko dla operatora bank-demo.");
        }
        var order = orderService.get(orderId, customerId);
        if (!OrderStatus.PENDING.name().equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "To zamowienie nie jest gotowe do platnosci.");
        }
        var normalizedCurrency = normalizeCurrency(currency);
        var quote = bankClient.cardQuote(new BankCardQuoteRequest(order.total(), normalizedCurrency));
        validateQuote(quote, amount, normalizedCurrency);

        return createSession(order, amount, normalizedCurrency, session);
    }

    public CardCheckoutSessionResponse startFromCart(
            String customerId,
            String currency,
            BigDecimal amount,
            HttpSession session
    ) {
        if (!"bank-demo".equalsIgnoreCase(paymentProperties.getProvider())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Wewnętrzny formularz jest dostępny tylko dla operatora bank-demo.");
        }
        var cart = cartService.getCart(customerId);
        if (cart.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Koszyk jest pusty.");
        }
        if (!"PLN".equals(cart.currency())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Kasa sklepu oczekuje cen w PLN.");
        }

        var normalizedCurrency = normalizeCurrency(currency);
        var quote = bankClient.cardQuote(new BankCardQuoteRequest(cart.total(), normalizedCurrency));
        validateQuote(quote, amount, normalizedCurrency);

        // Create the order only after the current cart and currency quote are valid.
        var order = orderService.create(customerId);
        return createSession(order, amount, normalizedCurrency, session);
    }

    private CardCheckoutSessionResponse createSession(
            OrderResponse order,
            BigDecimal amount,
            String currency,
            HttpSession session
    ) {
        var expiresAt = Instant.now().plus(SESSION_TTL);
        var idempotencyKey = ShopInput.idempotencyKey("card-" + UUID.randomUUID());
        session.setAttribute(ORDER_ID, order.id());
        session.setAttribute(ORDER_NUMBER, order.orderNumber());
        session.setAttribute(AMOUNT, amount.toPlainString());
        session.setAttribute(CURRENCY, currency);
        session.setAttribute(IDEMPOTENCY_KEY, idempotencyKey);
        session.setAttribute(EXPIRES_AT, expiresAt.toString());
        return response(order.id(), order.orderNumber(), amount, currency, idempotencyKey, expiresAt);
    }

    public CardCheckoutSessionResponse current(HttpSession session) {
        var expiresAt = Instant.parse(required(session, EXPIRES_AT));
        if (Instant.now().isAfter(expiresAt)) {
            clear(session);
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Sesja platnosci wygasla. Wroc do kasy i rozpocznij platnosc ponownie.");
        }
        return response(
                ((Number) requiredObject(session, ORDER_ID)).longValue(),
                required(session, ORDER_NUMBER),
                new BigDecimal(required(session, AMOUNT)),
                required(session, CURRENCY),
                required(session, IDEMPOTENCY_KEY),
                expiresAt
        );
    }

    public void requireMatches(long orderId, String currency, BigDecimal amount, HttpSession session) {
        var current = current(session);
        if (!current.orderId().equals(orderId)
                || !current.currency().equals(currency)
                || current.amount().compareTo(amount) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dane platnosci zmienily sie. Rozpocznij platnosc ponownie.");
        }
    }

    public void clear(HttpSession session) {
        session.removeAttribute(ORDER_ID);
        session.removeAttribute(ORDER_NUMBER);
        session.removeAttribute(AMOUNT);
        session.removeAttribute(CURRENCY);
        session.removeAttribute(IDEMPOTENCY_KEY);
        session.removeAttribute(EXPIRES_AT);
    }

    private static CardCheckoutSessionResponse response(
            Long orderId, String orderNumber, BigDecimal amount, String currency,
            String idempotencyKey, Instant expiresAt
    ) {
        return new CardCheckoutSessionResponse(
                orderId, orderNumber, amount, currency, idempotencyKey, expiresAt, "/card-provider.html"
        );
    }

    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "PLN";
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private static void validateQuote(BankCardQuoteResponse quote, BigDecimal amount, String currency) {
        if (quote == null || quote.amount() == null || quote.currency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Bank nie zwrocil poprawnej wyceny platnosci.");
        }
        var quotedCurrency = normalizeCurrency(quote.currency());
        if (!currency.equals(quotedCurrency) || quote.amount().compareTo(amount) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Kurs platnosci wygasl. Pobierz nowe przeliczenie.");
        }
    }

    private static String required(HttpSession session, String name) {
        var value = session.getAttribute(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Sesja platnosci nie istnieje. Wroc do kasy.");
        }
        return string;
    }

    private static Object requiredObject(HttpSession session, String name) {
        var value = session.getAttribute(name);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Sesja platnosci nie istnieje. Wroc do kasy.");
        }
        return value;
    }
}
