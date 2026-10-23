package org.demo.shopapp.order;
import org.demo.shopapp.payment.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CardOrderPaymentServiceTest {
    @Test void ambiguousHttpConflictDoesNotBecomeDefinitiveRefusal() {
        var states=mock(OrderStateService.class);
        var attempts=mock(PaymentAttemptService.class);
        var bank=mock(BankAuthorizationClient.class);
        var attempt=PaymentFixtures.attempt("CARD");
        when(attempts.begin(7L,"customer","key","CARD",BigDecimal.TEN,"PLN"))
                .thenReturn(new PaymentAttemptService.Prepared(PaymentFixtures.order(OrderStatus.PENDING),attempt,true));
        when(bank.payCard(any(),eq("key"))).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        when(attempts.order(attempt)).thenReturn(PaymentFixtures.order(OrderStatus.UNKNOWN));
        var result=new CardOrderPaymentService(states,bank,mock(OrderCartCleanupService.class),attempts)
                .pay(7L,"customer","key",new CardOrderPaymentRequest("synthetic","123","12/30","PLN",BigDecimal.TEN));
        assertThat(result.status()).isEqualTo("UNKNOWN");
        verify(attempts).unknown(attempt);
        verify(attempts,never()).decline(any(),anyString());
        verifyNoInteractions(states);
    }
}
