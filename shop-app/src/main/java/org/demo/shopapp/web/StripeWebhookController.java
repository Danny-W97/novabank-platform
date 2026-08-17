package org.demo.shopapp.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.demo.shopapp.cart.CartService;
import org.demo.shopapp.config.ShopPaymentProperties;
import org.demo.shopapp.order.OrderStateService;
import org.demo.shopapp.payment.StripeWebhookVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/payments/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final ObjectMapper objectMapper;
    private final ShopPaymentProperties properties;
    private final OrderStateService orderStateService;
    private final CartService cartService;

    public StripeWebhookController(ObjectMapper objectMapper, ShopPaymentProperties properties,
                                    OrderStateService orderStateService, CartService cartService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orderStateService = orderStateService;
        this.cartService = cartService;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    String webhook(@RequestBody String payload, HttpServletRequest request) {
        if (!properties.isStripe() || !StripeWebhookVerifier.isValid(
                payload, request.getHeader("Stripe-Signature"), properties.getWebhookSecret(), Instant.now())) {
            log.warn("Stripe webhook rejected: invalid signature or provider configuration.");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieprawidlowy podpis webhooka.");
        }
        try {
            JsonNode event = objectMapper.readTree(payload);
            var type = event.path("type").asText();
            if (!"checkout.session.completed".equals(type)
                    && !"checkout.session.async_payment_succeeded".equals(type)) {
                return "ignored";
            }
            var session = event.path("data").path("object");
            if (!"paid".equals(session.path("payment_status").asText())) {
                return "pending";
            }
            var orderNumber = session.path("metadata").path("orderNumber").asText();
            var paymentId = session.path("id").asText();
            var order = orderStateService.findByExternalPaymentId(paymentId);
            if (!"stripe".equals(order.paymentProvider())) {
                log.warn("Stripe webhook rejected: provider mismatch for session {} and order {}.",
                        paymentId, order.orderNumber());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook nie pasuje do zamowienia.");
            }
            if (!orderNumber.isBlank() && !order.orderNumber().equals(orderNumber)) {
                log.warn("Stripe webhook rejected: metadata order {} does not match stored order {} for session {}.",
                        orderNumber, order.orderNumber(), paymentId);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook nie pasuje do zamowienia.");
            }
            var amount = BigDecimal.valueOf(session.path("amount_total").asLong()).movePointLeft(2);
            var metadata = session.path("metadata");
            var expectedAmount = new BigDecimal(metadata.path("chargedAmount").asText("0"));
            var expectedCurrency = metadata.path("chargedCurrency").asText();
            if (amount.compareTo(expectedAmount) != 0 || !expectedCurrency.equalsIgnoreCase(session.path("currency").asText())) {
                log.warn("Stripe webhook rejected: amount/currency mismatch for order {} and session {}. " +
                                "received {} {}, expected {} {}.",
                        order.orderNumber(), paymentId, amount, session.path("currency").asText(),
                        expectedAmount, expectedCurrency);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kwota webhooka nie pasuje do zamowienia.");
            }
            var paid = orderStateService.markPaidExternal(order.id(), paymentId);
            clearCart(paid.customerId());
            return "processed";
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            var eventType = "unknown";
            var eventId = "unknown";
            try {
                var event = objectMapper.readTree(payload);
                eventType = event.path("type").asText("unknown");
                eventId = event.path("id").asText("unknown");
            } catch (Exception ignored) {
                // Keep the original response when the payload cannot be parsed.
            }
            log.error("Stripe webhook processing failed for event {} (type {}).", eventId, eventType, exception);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Niepoprawne dane webhooka.", exception);
        }
    }

    @GetMapping("/success")
    ResponseEntity<Void> success(@RequestParam String order, @RequestParam(required = false) String session_id) {
        var row = orderStateService.findByOrderNumber(order);
        var location = "/novatech_shop.html?payment=" + (row.status().name().equals("PAID") ? "paid" : "pending")
                + "&order=" + order;
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    @GetMapping("/cancel")
    ResponseEntity<Void> cancel(@RequestParam String order) {
        var row = orderStateService.findByOrderNumber(order);
        if (row.status().name().equals("PAID")) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "/novatech_shop.html?payment=paid&order=" + row.orderNumber())
                    .build();
        }
        var failed = orderStateService.markFailed(row.id(), "Platnosc zostala anulowana.");
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/novatech_shop.html?payment=failed&order=" + failed.orderNumber())
                .build();
    }

    private void clearCart(String customerId) {
        try { cartService.clear(customerId); } catch (RuntimeException ignored) { }
    }
}
