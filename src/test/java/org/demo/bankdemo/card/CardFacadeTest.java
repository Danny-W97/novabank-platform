package org.demo.bankdemo.card;

import org.demo.bankdemo.currency.CurrencyCode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CardFacadeTest {
    final CardQueryService queries=mock(CardQueryService.class);
    final CardLifecycleService lifecycle=mock(CardLifecycleService.class);
    final CardPaymentService payments=mock(CardPaymentService.class);
    final OnlineCardPaymentService online=mock(OnlineCardPaymentService.class);
    final CardService facade=new CardService(queries,lifecycle,payments,online);

    @Test void routesReadsAndLifecycleWithoutIntroducingFinancialSideEffects() {
        facade.cardFor("synthetic@example.invalid"); facade.dailyLimitFor("synthetic@example.invalid");
        facade.onlinePaymentStatus("key","merchant","order");
        facade.attach("synthetic@example.invalid"); facade.block("synthetic@example.invalid");
        facade.blockTemporarily("synthetic@example.invalid",5); facade.activate("synthetic@example.invalid");
        facade.updateDailyLimit("synthetic@example.invalid",BigDecimal.TEN);
        verify(queries).cardFor("synthetic@example.invalid"); verify(queries).dailyLimitFor("synthetic@example.invalid");
        verify(queries).onlinePaymentStatus("key","merchant","order");
        verify(lifecycle).attach("synthetic@example.invalid"); verify(lifecycle).block("synthetic@example.invalid");
        verify(lifecycle).blockTemporarily("synthetic@example.invalid",5); verify(lifecycle).activate("synthetic@example.invalid");
        verify(lifecycle).updateDailyLimit("synthetic@example.invalid",BigDecimal.TEN);
        verifyNoInteractions(payments,online);
    }
    @Test void defaultsToInStoreAndForwardsResultAndInfrastructureFailureUnchanged() {
        var result=new CardService.TransactionResult(CurrencyCode.PLN,BigDecimal.TEN,CardTransactionStatus.COMPLETED,null,CardPaymentChannel.IN_STORE);
        when(payments.pay("email","shop",CurrencyCode.PLN,BigDecimal.TEN,CardPaymentChannel.IN_STORE,"key")).thenReturn(result);
        assertThat(facade.pay("email","shop",CurrencyCode.PLN,BigDecimal.TEN,"key")).isSameAs(result);
        var failure=new IllegalStateException("synthetic infrastructure failure");
        when(payments.pay("email","shop",CurrencyCode.PLN,BigDecimal.TEN,CardPaymentChannel.ONLINE,"other")).thenThrow(failure);
        assertThatThrownBy(()->facade.pay("email","shop",CurrencyCode.PLN,BigDecimal.TEN,CardPaymentChannel.ONLINE,"other")).isSameAs(failure);
        verifyNoInteractions(queries,lifecycle,online);
    }
    @Test void onlinePaymentForwardsEveryArgumentAndDoesNotTranslateFailuresIntoDeclines() {
        var failure=new IllegalStateException("synthetic uncertain outcome");
        when(online.payOnline("number","cvv","expiry","merchant","order",CurrencyCode.EUR,BigDecimal.TEN,
                BigDecimal.ONE,"title","key","merchant-id")).thenThrow(failure);
        assertThatThrownBy(()->facade.payOnline("number","cvv","expiry","merchant","order",CurrencyCode.EUR,
                BigDecimal.TEN,BigDecimal.ONE,"title","key","merchant-id")).isSameAs(failure);
        verifyNoInteractions(queries,lifecycle,payments);
    }
}
