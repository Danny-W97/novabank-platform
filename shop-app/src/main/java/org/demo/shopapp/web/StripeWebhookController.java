package org.demo.shopapp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.demo.shopapp.config.ShopPaymentProperties;
import org.demo.shopapp.order.*;
import org.demo.shopapp.payment.StripeWebhookVerifier;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.ParameterizedTypeReference;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/payments/stripe")
public class StripeWebhookController {
    private final ObjectMapper objectMapper;
    private final ShopPaymentProperties properties;
    private final OrderStateService orders;
    private final PaymentAttemptRepository attempts;
    private final PaymentResolutionService resolution;

    public StripeWebhookController(ObjectMapper objectMapper, ShopPaymentProperties properties,
            OrderStateService orders, PaymentAttemptRepository attempts, PaymentResolutionService resolution) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.orders = orders;
        this.attempts = attempts;
        this.resolution = resolution;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    String webhook(@RequestBody String payload, HttpServletRequest request) {
        if (!properties.isStripe() || !StripeWebhookVerifier.isValid(payload,
                request.getHeader("Stripe-Signature"), properties.getWebhookSecret(), Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe webhook signature.");
        }
        try {
            var event = objectMapper.readTree(payload);
            var type = event.path("type").asText();
            if (!"checkout.session.completed".equals(type)
                    && !"checkout.session.async_payment_succeeded".equals(type)) return "ignored";
            var session = event.path("data").path("object");
            var attemptId = session.path("metadata").path("attemptId").asText();
            var attempt = attempts.byId(attemptId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.CONFLICT, "Unknown or legacy payment attempt; operator review required."));
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.convertValue(session, Map.class);
            resolution.acceptStripe(attempt, body);
            return "processed";
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Stripe webhook could not be reconciled; financial state was not overwritten.");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe payment event.");
        }
    }

    @GetMapping("/success")
    ResponseEntity<Void> success(@RequestParam String order, @RequestParam(required = false) String session_id) {
        return redirect(orders.findByOrderNumber(order));
    }

    @GetMapping("/cancel")
    ResponseEntity<Void> cancel(@RequestParam String order) {
        // Browser cancellation is not proof of financial cancellation.
        return redirect(orders.findByOrderNumber(order));
    }

    private ResponseEntity<Void> redirect(OrderRepository.OrderRow row) {
        var url = org.springframework.web.util.UriComponentsBuilder.fromPath("/novatech_shop.html")
                .queryParam("payment", row.status() == OrderStatus.PAID ? "paid" : "pending")
                .queryParam("order", row.orderNumber()).build().encode().toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url).build();
    }
}
