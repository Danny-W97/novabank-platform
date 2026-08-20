package org.demo.shopapp.order;

import org.demo.shopapp.config.ShopOrderProperties;
import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankAuthorizationStatusResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentReconciliationJobTest {

    private static final String AUTHORIZATION_ID = "auth-1";
    private static final String CUSTOMER_ACCOUNT = "11111111111111111111111111";

    @Test
    void completedBankAuthorizationMarksOrderPaidAndCleansPurchasedItems() {
        var orderRepository = mock(OrderRepository.class);
        var orderStateService = mock(OrderStateService.class);
        var bankClient = mock(BankAuthorizationClient.class);
        var cartCleanupService = mock(OrderCartCleanupService.class);
        var properties = properties();
        var job = new PaymentReconciliationJob(
                orderRepository, orderStateService, bankClient, cartCleanupService, properties
        );
        var pending = order(OrderStatus.PENDING);
        var paid = order(OrderStatus.PAID);

        when(orderRepository.findReconciliationCandidates(anyInt(), any())).thenReturn(List.of(pending));
        when(bankClient.status(AUTHORIZATION_ID)).thenReturn(new BankAuthorizationStatusResponse(
                AUTHORIZATION_ID, "order-1", BigDecimal.TEN, "PLN", "COMPLETED",
                "BANK-REF-1", CUSTOMER_ACCOUNT, null, null
        ));
        when(orderStateService.markPaid(7L, "payment-1", "BANK-REF-1", CUSTOMER_ACCOUNT))
                .thenReturn(paid);

        job.reconcilePendingPayments();

        verify(orderStateService).markPaid(7L, "payment-1", "BANK-REF-1", CUSTOMER_ACCOUNT);
        verify(cartCleanupService).cleanupPaidOrder(paid);
        verify(orderStateService).markPaymentReconciled(7L);
    }

    @Test
    void mismatchedBankStatusDoesNotChangeOrder() {
        var orderRepository = mock(OrderRepository.class);
        var orderStateService = mock(OrderStateService.class);
        var bankClient = mock(BankAuthorizationClient.class);
        var cartCleanupService = mock(OrderCartCleanupService.class);
        var job = new PaymentReconciliationJob(
                orderRepository, orderStateService, bankClient, cartCleanupService, properties()
        );
        var pending = order(OrderStatus.PENDING);

        when(orderRepository.findReconciliationCandidates(anyInt(), any())).thenReturn(List.of(pending));
        when(bankClient.status(AUTHORIZATION_ID)).thenReturn(new BankAuthorizationStatusResponse(
                AUTHORIZATION_ID, "different-order", BigDecimal.TEN, "PLN", "COMPLETED",
                "BANK-REF-1", CUSTOMER_ACCOUNT, null, null
        ));

        job.reconcilePendingPayments();

        verify(orderStateService, never()).markPaid(anyLong(), any(), any(), any());
        verify(orderStateService, never()).markFailed(anyLong(), any());
        verify(cartCleanupService, never()).cleanupPaidOrder(any());
    }

    private static ShopOrderProperties properties() {
        var properties = new ShopOrderProperties();
        properties.setReconciliationEnabled(true);
        properties.setReconciliationIntervalMs(30000);
        properties.setReconciliationBatchSize(50);
        return properties;
    }

    private static OrderRepository.OrderRow order(OrderStatus status) {
        return new OrderRepository.OrderRow(
                7L, "order-1", "customer-1", CUSTOMER_ACCOUNT,
                "22222222222222222222222222", BigDecimal.TEN, "PLN", status,
                "payment-1", AUTHORIZATION_ID, "state-1", null, null, List.of()
        );
    }
}
