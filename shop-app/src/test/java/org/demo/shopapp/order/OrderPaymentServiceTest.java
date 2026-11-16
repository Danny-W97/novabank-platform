package org.demo.shopapp.order;
import org.demo.shopapp.payment.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderPaymentServiceTest {
    @Test void lostCreateReplyStaysUnknownAndDoesNotWriteFailed() {
        var states=mock(OrderStateService.class);
        var attempts=mock(PaymentAttemptService.class);
        var bank=mock(BankAuthorizationClient.class);
        var attempt=PaymentFixtures.attempt("BANK");
        when(attempts.begin(7L,"customer","key","BANK",null,null))
                .thenReturn(new PaymentAttemptService.Prepared(PaymentFixtures.order(OrderStatus.PENDING),attempt,true));
        when(bank.create(any())).thenThrow(new IllegalStateException("lost response"));
        when(attempts.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.UNKNOWN));
        var result=new OrderPaymentService(states,bank,attempts).pay(7L,"customer","key");
        assertThat(result.status()).isEqualTo("UNKNOWN");
        verify(attempts).unknown(attempt);
        verifyNoInteractions(states);
    }
    @Test void repeatedUnknownDoesNotCreateAnotherAuthorization() {
        var attempts=mock(PaymentAttemptService.class);
        var bank=mock(BankAuthorizationClient.class);
        when(attempts.begin(7L,"customer","key","BANK",null,null))
                .thenReturn(new PaymentAttemptService.Prepared(PaymentFixtures.order(OrderStatus.UNKNOWN),PaymentFixtures.attempt("BANK"),false));
        var result=new OrderPaymentService(mock(OrderStateService.class),bank,attempts).pay(7L,"customer","key");
        assertThat(result.replayed()).isTrue();
        verifyNoInteractions(bank);
    }
    @Test void successfulCreateAttachesTheDurableCorrelation() {
        var attempts=mock(PaymentAttemptService.class);
        var bank=mock(BankAuthorizationClient.class);
        var attempt=PaymentFixtures.attempt("BANK");
        when(attempts.begin(7L,"customer","key","BANK",null,null))
                .thenReturn(new PaymentAttemptService.Prepared(PaymentFixtures.order(OrderStatus.PENDING),attempt,true));
        when(bank.create(any())).thenReturn(new BankAuthorizationResponse("auth","http://localhost:8080/shop/authorize/auth","state",null,"merchant"));
        when(attempts.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.PENDING));
        var result=new OrderPaymentService(mock(OrderStateService.class),bank,attempts).pay(7L,"customer","key");
        verify(attempts).attachAuthorization(attempt,"auth","http://localhost:8080/shop/authorize/auth");
        assertThat(result.authorizationUrl()).contains("/auth");
    }
    @Test void alreadyPaidDoesNotContactBank() {
        var attempts=mock(PaymentAttemptService.class);
        var bank=mock(BankAuthorizationClient.class);
        when(attempts.begin(7L,"customer","key","BANK",null,null))
                .thenReturn(new PaymentAttemptService.Prepared(PaymentFixtures.order(OrderStatus.PAID),null,false));
        assertThat(new OrderPaymentService(mock(OrderStateService.class),bank,attempts).pay(7L,"customer","key").replayed()).isTrue();
        verifyNoInteractions(bank);
    }
}
