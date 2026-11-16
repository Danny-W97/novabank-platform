package org.demo.shopapp.order;

import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.StripeCheckoutClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.Map;

/** Network calls outside DB transactions; only verified outcomes change the order. */
@Service
public class PaymentResolutionService {
    private final PaymentAttemptService states;
    private final PaymentAttemptRepository attempts;
    private final BankAuthorizationClient bank;
    private final StripeCheckoutClient stripe;
    private final OrderCartCleanupService cleanup;

    public PaymentResolutionService(PaymentAttemptService states, PaymentAttemptRepository attempts,
            BankAuthorizationClient bank, StripeCheckoutClient stripe, OrderCartCleanupService cleanup) {
        this.states = states;
        this.attempts = attempts;
        this.bank = bank;
        this.stripe = stripe;
        this.cleanup = cleanup;
    }

    public void refresh(PaymentAttempt attempt) {
        var order = states.order(attempt);
        if (attempt.succeeded()) {
            clean(order);
            states.checked(attempt);
            return;
        }
        switch (attempt.provider()) {
            case "BANK" -> {
                var result = attempt.authorizationId() == null ? bank.statusByKey(attempt.key()) : bank.status(attempt.authorizationId());
                if (result == null || result.authorizationId()==null
                        || (attempt.authorizationId()!=null && !attempt.authorizationId().equals(result.authorizationId()))
                        || !attempt.merchantId().equals(result.merchantId())
                        || !order.orderNumber().equals(result.shopOrderId()) || result.amount() == null
                        || order.total().compareTo(result.amount()) != 0 || !order.currency().equals(result.currency())) throw mismatch();
                if (attempt.authorizationId() == null) {
                    states.attachAuthorization(attempt, result.authorizationId(), bank.authorizationUrl(result.authorizationId()));
                    attempt = attempts.byId(attempt.id()).orElseThrow();
                }
                switch (result.status()) {
                    case "COMPLETED", "REDEEMED" -> complete(attempt, result.referenceNumber(), result.customerAccountNumber());
                    case "FAILED" -> states.decline(attempt, "Bank confirmed a terminal refusal.");
                    case "PENDING" -> { }
                    default -> throw mismatch();
                }
            }
            case "CARD" -> {
                var result = bank.cardStatus(attempt.key(), order.merchantAccountNumber(), order.orderNumber());
                if (result == null || !attempt.merchantId().equals(result.merchantId())
                        || !order.orderNumber().equals(result.orderId()) || result.amountInPln() == null
                        || order.total().compareTo(result.amountInPln()) != 0 || result.amount() == null
                        || attempt.chargedAmount().compareTo(result.amount()) != 0
                        || !attempt.chargedCurrency().equals(result.currency())) throw mismatch();
                switch (result.status()) {
                    case "COMPLETED" -> complete(attempt, result.referenceNumber(), result.sourceAccountNumber());
                    case "DECLINED" -> states.decline(attempt, "Bank confirmed a terminal card refusal.");
                    default -> throw mismatch();
                }
            }
            case "STRIPE" -> {
                if (attempt.externalId() == null) throw mismatch();
                acceptStripe(attempt, stripe.status(attempt.externalId()));
            }
            default -> throw mismatch();
        }
        states.checked(attempt);
    }

    public void acceptStripe(PaymentAttempt attempt, Map<String, Object> session) {
        validateStripe(attempt,session);
        states.attachExternal(attempt, (String)session.get("id"), attempt.checkoutUrl());
        if ("paid".equals(session.get("payment_status"))) clean(states.succeed(attempt, (String)session.get("id"), null));
        else if ("expired".equals(session.get("status")))
            throw new IllegalStateException("Expired Stripe session requires authoritative intent reconciliation.");
    }

    public boolean stripeCancellationConfirmed(PaymentAttempt attempt,Map<String,Object> session){
        validateStripe(attempt,session);
        if("paid".equals(session.get("payment_status"))){acceptStripe(attempt,session);return false;}
        if(!"expired".equals(session.get("status")) || !"unpaid".equals(session.get("payment_status")))return false;
        var intent=session.get("payment_intent");
        return session.containsKey("payment_intent") && (intent==null || (intent instanceof Map<?,?> pi && "canceled".equals(pi.get("status"))
                && pi.get("amount_received") instanceof Number amount && new BigDecimal(amount.toString()).signum()==0
                && pi.get("amount") instanceof Number requested && attempt.chargedAmount().compareTo(new BigDecimal(requested.toString()).movePointLeft(2))==0
                && attempt.chargedCurrency().equalsIgnoreCase(String.valueOf(pi.get("currency")))));
    }

    private void validateStripe(PaymentAttempt attempt,Map<String,Object> session){
        var order = states.order(attempt);
        if (!"STRIPE".equals(attempt.provider()) || session == null
                || !(session.get("id") instanceof String externalId) || externalId.isBlank()
                || (attempt.externalId() != null && !attempt.externalId().equals(externalId))
                || !(session.get("metadata") instanceof Map<?, ?> metadata)
                || !attempt.id().equals(metadata.get("attemptId"))
                || !attempt.merchantId().equals(metadata.get("merchantId"))
                || !order.orderNumber().equals(metadata.get("orderNumber"))
                || !order.orderNumber().equals(session.get("client_reference_id"))
                || !(session.get("amount_total") instanceof Number amount)
                || attempt.chargedAmount().compareTo(new BigDecimal(amount.toString()).movePointLeft(2)) != 0
                || !attempt.chargedCurrency().equalsIgnoreCase(String.valueOf(session.get("currency")))) throw mismatch();
    }

    private void complete(PaymentAttempt attempt, String reference, String account) {
        if (account == null || !account.matches("\\d{26}")) throw mismatch();
        clean(states.succeed(attempt, reference, account));
    }
    private void clean(OrderRepository.OrderRow order) {
        try { cleanup.cleanupPaidOrder(order); }
        catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Paid order cart cleanup requires retry; order ID {}.", order.id());
        }
    }
    private static ResponseStatusException mismatch() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Provider outcome does not match the durable payment attempt.");
    }
}
