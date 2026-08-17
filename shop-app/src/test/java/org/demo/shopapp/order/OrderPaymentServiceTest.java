package org.demo.shopapp.order;

import org.demo.shopapp.payment.BankAuthorizationClient;
import org.demo.shopapp.payment.BankAuthorizationResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class OrderPaymentServiceTest {

    @Test
    void retryAfterPaidDoesNotCallBankAgain() {
        var stateService = mock(OrderStateService.class);
        var bankClient = mock(BankAuthorizationClient.class);
        var service = new OrderPaymentService(stateService, bankClient);
        var paid = row(OrderStatus.PAID, "bank-ref", null);
        when(stateService.preparePayment(7L, "customer-1", "payment-1"))
                .thenReturn(paid);

        var response = service.pay(7L, "customer-1", "payment-1");

        assertThat(response.status()).isEqualTo("PAID");
        assertThat(response.replayed()).isTrue();
        verify(bankClient, never()).create(any());
    }

    @Test
    void successfulBankResponseChangesPendingOrderToPaid() {
        var stateService = mock(OrderStateService.class);
        var bankClient = mock(BankAuthorizationClient.class);
        var service = new OrderPaymentService(stateService, bankClient);
        var pending = row(OrderStatus.PENDING, null, "payment-1");
        var linked = row(OrderStatus.PENDING, null, "payment-1");
        when(stateService.preparePayment(7L, "customer-1", "payment-1"))
                .thenReturn(pending);
        when(bankClient.create(any()))
                .thenReturn(new BankAuthorizationResponse("auth-1", "http://localhost:8080/shop/authorize/auth-1", "state-1", null));
        when(stateService.findByAuthorizationId("auth-1")).thenReturn(linked);

        var response = service.pay(7L, "customer-1", "payment-1");

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.authorizationUrl()).endsWith("auth-1");
        verify(stateService).attachAuthorization(eq(7L), eq("customer-1"), eq("auth-1"), anyString());
    }

    @Test
    void bankFailureIsPersistedAsFailedAndCanBeRetriedLater() {
        var stateService = mock(OrderStateService.class);
        var bankClient = mock(BankAuthorizationClient.class);
        var service = new OrderPaymentService(stateService, bankClient);
        var pending = row(OrderStatus.PENDING, null, "payment-1");
        var failed = row(OrderStatus.FAILED, null, "payment-1");
        when(stateService.preparePayment(7L, "customer-1", "payment-1"))
                .thenReturn(pending);
        when(bankClient.create(any())).thenThrow(new RuntimeException("Bank unavailable"));
        when(stateService.markFailed(eq(7L), eq("Bank unavailable"))).thenReturn(failed);

        var response = service.pay(7L, "customer-1", "payment-1");

        assertThat(response.status()).isEqualTo("FAILED");
        verify(stateService).markFailed(7L, "Bank unavailable");
    }

    private static OrderRepository.OrderRow row(
            OrderStatus status,
            String bankReference,
            String idempotencyKey
    ) {
        return new OrderRepository.OrderRow(
                7L, "order-1", "customer-1",
                "11111111111111111111111111",
                "22222222222222222222222222",
                BigDecimal.TEN, "PLN", status, idempotencyKey,
                null, null, bankReference, null, List.of()
        );
    }
}
