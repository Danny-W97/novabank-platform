package org.demo.shopapp.web;

import org.demo.shopapp.order.*;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankAuthorizationRedeemRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/payment")
public class ShopPaymentCallbackController {
    private final PaymentAttemptRepository attempts;
    private final PaymentAttemptService states;
    private final PaymentResolutionService resolution;
    private final BankAuthorizationClient bank;

    public ShopPaymentCallbackController(PaymentAttemptRepository attempts, PaymentAttemptService states,
            PaymentResolutionService resolution, BankAuthorizationClient bank) {
        this.attempts = attempts;
        this.states = states;
        this.resolution = resolution;
        this.bank = bank;
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam String authorizationId, @RequestParam String state,
            @RequestParam String status, @RequestParam(required = false) String code) {
        var attempt = attempts.byAuthorization(authorizationId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Payment attachment is missing; reconciliation is required."));
        if (!java.security.MessageDigest.isEqual(state.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                attempt.correlation().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid callback state.");
        }
        // Redirect parameters are not financial truth; failed redeem does not mean no debit.
        if ("approved".equals(status) && code != null && !code.isBlank()) {
            try { bank.redeem(authorizationId, new BankAuthorizationRedeemRequest(code, state)); }
            catch (RuntimeException ignored) { /* Status also handles a lost redeem response. */ }
        }
        try { resolution.refresh(attempt); }
        catch (RuntimeException exception) { states.unknown(attempt); }
        var order = states.order(attempt);
        var result = order.status() == OrderStatus.PAID ? "paid" : order.status() == OrderStatus.FAILED ? "failed" : "pending";
        var url = UriComponentsBuilder.fromPath("/novatech_shop.html").queryParam("payment", result)
                .queryParam("order", order.orderNumber()).build().encode().toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url).build();
    }
}
