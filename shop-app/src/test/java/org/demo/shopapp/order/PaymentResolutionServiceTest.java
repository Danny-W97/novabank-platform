package org.demo.shopapp.order;

import org.demo.shopapp.payment.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentResolutionServiceTest {
    final PaymentAttemptService states=mock(PaymentAttemptService.class);
    final PaymentAttemptRepository attempts=mock(PaymentAttemptRepository.class);
    final BankAuthorizationClient bank=mock(BankAuthorizationClient.class);
    final StripeCheckoutClient stripe=mock(StripeCheckoutClient.class);
    final OrderCartCleanupService cleanup=mock(OrderCartCleanupService.class);
    final PaymentResolutionService service=new PaymentResolutionService(states,attempts,bank,stripe,cleanup);

    @Test void stripeWithCardLikeKeyNeverCallsBankCardStatus() {
        var a=PaymentFixtures.attempt("STRIPE");
        var attempt=new PaymentAttempt(a.id(),a.orderId(),a.merchantId(),a.provider(),"card-key",a.state(),a.correlation(),
                a.chargedAmount(),a.chargedCurrency(),a.authorizationId(),a.authorizationUrl(),a.externalId(),a.checkoutUrl(),
                a.reference(),a.customerAccount(),a.createdAt(),a.checkedAt(),a.version());
        when(states.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.PENDING));
        when(stripe.status("session")).thenReturn(session(attempt,"paid",1000));
        when(states.succeed(attempt,"session",null)).thenReturn(PaymentFixtures.order(OrderStatus.PAID));
        service.refresh(attempt);
        verifyNoInteractions(bank);
        verify(states).succeed(attempt,"session",null);
    }
    @Test void stripeAmountIsComparedWithDurableAttemptNotOnlyWebhookMetadata() {
        var attempt=PaymentFixtures.attempt("STRIPE");
        when(states.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.PENDING));
        assertThatThrownBy(()->service.acceptStripe(attempt,session(attempt,"paid",999))).isInstanceOf(RuntimeException.class);
        verify(states,never()).succeed(any(),any(),any());
    }
    @Test void confirmedBankPaymentMarksPaidAndCartCleanupFailureCannotUndoIt() {
        var attempt=PaymentFixtures.attempt("BANK");
        when(states.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.UNKNOWN));
        when(bank.status("auth")).thenReturn(new BankAuthorizationStatusResponse("auth","order",BigDecimal.TEN,"PLN",
                "REDEEMED","REF","11111111111111111111111111",null,null,"merchant"));
        var paid=PaymentFixtures.order(OrderStatus.PAID);
        when(states.succeed(attempt,"REF","11111111111111111111111111")).thenReturn(paid);
        doThrow(new IllegalStateException()).when(cleanup).cleanupPaidOrder(paid);
        service.refresh(attempt);
        verify(states).succeed(attempt,"REF","11111111111111111111111111");
        verify(states,never()).decline(any(),any());
        verify(states).checked(attempt);
    }
    @Test void mismatchedBankMerchantCannotMarkOrderPaid() {
        var attempt=PaymentFixtures.attempt("BANK");
        when(states.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.PENDING));
        when(bank.status("auth")).thenReturn(new BankAuthorizationStatusResponse("auth","order",BigDecimal.TEN,"PLN",
                "COMPLETED","REF","11111111111111111111111111",null,null,"other-merchant"));
        assertThatThrownBy(()->service.refresh(attempt)).isInstanceOf(RuntimeException.class);
        verify(states,never()).succeed(any(),any(),any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"id","client_reference_id","currency","attemptId","merchantId","orderNumber"})
    void mismatchedStripeIdentityOrCurrencyCannotChangeAttempt(String field) {
        var attempt=PaymentFixtures.attempt("STRIPE");
        when(states.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.PENDING));
        var body=new java.util.HashMap<String,Object>(session(attempt,"paid",1000));
        if(java.util.Set.of("attemptId","merchantId","orderNumber").contains(field)) {
            var metadata=new java.util.HashMap<String,Object>((Map<String,Object>)body.get("metadata"));
            metadata.put(field,"other-synthetic-value");body.put("metadata",metadata);
        } else body.put(field,"other-synthetic-value");
        assertThatThrownBy(()->service.acceptStripe(attempt,body)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(states,never()).succeed(any(),any(),any());
        verify(states,never()).attachExternal(any(),any(),any());
    }
    @Test void expiredStripeSessionDoesNotReleaseOrderAsDefinitivelyUnpaid() {
        var attempt=PaymentFixtures.attempt("STRIPE");
        when(states.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.UNKNOWN));
        var body=new java.util.HashMap<String,Object>(session(attempt,"unpaid",1000));body.put("status","expired");
        assertThatThrownBy(()->service.acceptStripe(attempt,body)).isInstanceOf(IllegalStateException.class);
        verify(states,never()).decline(any(),any()); verify(states,never()).succeed(any(),any(),any());
    }
    static Map<String,Object> session(PaymentAttempt a,String state,int amount) {
        return Map.of("id","session","client_reference_id","order","payment_status",state,"status","complete",
                "amount_total",amount,"currency","pln","metadata",Map.of("attemptId",a.id(),"merchantId",a.merchantId(),"orderNumber","order"));
    }
}
