package org.demo.shopapp.order;

import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankAuthorizationRedeemResponse;
import org.demo.shopapp.payment.BankRefundRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceTest {

    private static final String KEY = "refund-order-1";
    private static final String CUSTOMER_ACCOUNT = "11111111111111111111111111";
    private static final String MERCHANT_ACCOUNT = "22222222222222222222222222";

    @Test
    void retriesPendingRefundWithTheSameKeyAfterUnknownBankOutcome() {
        var orderRepository = mock(OrderRepository.class);
        var refundRepository = mock(RefundRepository.class);
        var bankClient = mock(BankAuthorizationClient.class);
        var service = new RefundService(orderRepository, refundRepository, bankClient, mock(org.springframework.transaction.PlatformTransactionManager.class));
        var order = order(OrderStatus.PAID);
        var pending = refund("PENDING", null, "timeout");
        var completed = refund("COMPLETED", "TRX-REFUND-1", null);

        when(orderRepository.findByIdForUpdate(7L, "customer-1")).thenReturn(order);
        when(refundRepository.findByIdempotencyKeyForUpdate(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(pending));
        when(refundRepository.existsCompletedForOrder(7L)).thenReturn(false);
        when(refundRepository.create(
                eq(7L), eq("order-1"), eq(KEY), eq(BigDecimal.TEN), eq("PLN"), eq("Customer request")
        )).thenReturn(pending);
        when(bankClient.refund(any(BankRefundRequest.class), eq(KEY)))
                .thenThrow(new RuntimeException("Bank timeout"))
                .thenReturn(new BankAuthorizationRedeemResponse(
                        "refund:order-1", BigDecimal.TEN, "PLN", "COMPLETED", "TRX-REFUND-1", CUSTOMER_ACCOUNT
                ));
        when(refundRepository.markPending(eq(KEY), eq("Wynik zwrotu niepotwierdzony. Ponów wyłącznie z tym samym kluczem."))).thenReturn(pending);
        when(refundRepository.markCompleted(KEY, "TRX-REFUND-1")).thenReturn(completed);

        var first = service.refund(7L, "customer-1", KEY, "Customer request");
        var second = service.refund(7L, "customer-1", KEY, "Customer request");

        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(second.status()).isEqualTo("COMPLETED");
        verify(bankClient, org.mockito.Mockito.times(2)).refund(any(BankRefundRequest.class), eq(KEY));
    }

    @Test
    void completedRefundWithTheSameKeyIsReplayedWithoutCallingBank() {
        var orderRepository = mock(OrderRepository.class);
        var refundRepository = mock(RefundRepository.class);
        var bankClient = mock(BankAuthorizationClient.class);
        var service = new RefundService(orderRepository, refundRepository, bankClient, mock(org.springframework.transaction.PlatformTransactionManager.class));
        var completed = refund("COMPLETED", "TRX-REFUND-1", null);

        when(orderRepository.findByIdForUpdate(7L, "customer-1")).thenReturn(order(OrderStatus.PAID));
        when(refundRepository.findByIdempotencyKeyForUpdate(KEY)).thenReturn(Optional.of(completed));

        var response = service.refund(7L, "customer-1", KEY, "Customer request");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.replayed()).isTrue();
        verify(bankClient, never()).refund(any(BankRefundRequest.class), eq(KEY));
    }

    private static OrderRepository.OrderRow order(OrderStatus status) {
        return new OrderRepository.OrderRow(
                7L, "order-1", "customer-1", CUSTOMER_ACCOUNT, MERCHANT_ACCOUNT,
                BigDecimal.TEN, "PLN", status, "payment-1", "auth-1", "state-1",
                "TRX-PAYMENT-1", null, List.of()
        );
    }

    private static RefundRepository.RefundRow refund(
            String status,
            String bankReference,
            String failureReason
    ) {
        return new RefundRepository.RefundRow(
                1L, 7L, "order-1", KEY, BigDecimal.TEN, "PLN", status,
                "Customer request", bankReference, failureReason
        );
    }
}
