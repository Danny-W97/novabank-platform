package org.demo.shopapp.order;
import org.demo.shopapp.config.ShopOrderProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentReconciliationJobTest {
    @Test void threeFailedChecksSuspendWithoutDeclaringRefusal() {
        var repository=mock(PaymentAttemptRepository.class);
        var states=mock(PaymentAttemptService.class);
        var resolution=mock(PaymentResolutionService.class);
        var attempt=PaymentFixtures.attempt("CARD");
        when(repository.candidates(any(),anyInt())).thenReturn(List.of(attempt),List.of(attempt),List.of(attempt),List.of());
        doThrow(new IllegalStateException("404")).when(resolution).refresh(attempt);
        when(states.failure(attempt)).thenReturn(1,2,3);
        var job=new PaymentReconciliationJob(repository,states,resolution,new ShopOrderProperties());
        for(int i=0;i<4;i++) job.reconcilePendingPayments();
        verify(resolution,times(3)).refresh(attempt);
        verify(states,times(3)).unknown(attempt);
        verify(states,times(3)).failure(attempt);
        verify(states,never()).decline(any(),anyString());
    }
    @Test void failingCounterDoesNotStopOtherAttempts() {
        var repository=mock(PaymentAttemptRepository.class);
        var states=mock(PaymentAttemptService.class);
        var resolution=mock(PaymentResolutionService.class);
        var first=PaymentFixtures.attempt("CARD");
        var next=PaymentFixtures.attempt("STRIPE");
        when(repository.candidates(any(),anyInt())).thenReturn(List.of(first,next));
        doThrow(new IllegalStateException()).when(resolution).refresh(first);
        doThrow(new IllegalStateException()).when(states).unknown(first);
        new PaymentReconciliationJob(repository,states,resolution,new ShopOrderProperties()).reconcilePendingPayments();
        verify(resolution).refresh(next);
    }
}
