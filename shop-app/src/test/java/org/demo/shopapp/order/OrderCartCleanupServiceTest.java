package org.demo.shopapp.order;

import org.demo.shopapp.cart.CartService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCartCleanupServiceTest {

    @Test
    void removesOnlyQuantitiesStoredInPaidOrder() {
        var orderRepository = mock(OrderRepository.class);
        var cartService = mock(CartService.class);
        var service = new OrderCartCleanupService(orderRepository, cartService);
        var order = paidOrder();
        when(orderRepository.claimCartCleanup(7L)).thenReturn(true);

        service.cleanupPaidOrder(order);

        verify(cartService).removePurchasedQuantities("customer-1", Map.of(101L, 3, 102L, 1));
    }

    @Test
    void repeatedCleanupDoesNotModifyCartAgain() {
        var orderRepository = mock(OrderRepository.class);
        var cartService = mock(CartService.class);
        var service = new OrderCartCleanupService(orderRepository, cartService);
        var order = paidOrder();
        when(orderRepository.claimCartCleanup(7L)).thenReturn(false);

        service.cleanupPaidOrder(order);

        verify(cartService, never()).removePurchasedQuantities("customer-1", Map.of(101L, 3, 102L, 1));
    }

    @Test
    void pendingOrderCannotCleanCart() {
        var orderRepository = mock(OrderRepository.class);
        var cartService = mock(CartService.class);
        var service = new OrderCartCleanupService(orderRepository, cartService);
        var pending = order(OrderStatus.PENDING);

        service.cleanupPaidOrder(pending);

        verify(orderRepository, never()).claimCartCleanup(7L);
        verify(cartService, never()).removePurchasedQuantities("customer-1", Map.of(101L, 3, 102L, 1));
    }

    private static OrderRepository.OrderRow paidOrder() {
        return order(OrderStatus.PAID);
    }

    private static OrderRepository.OrderRow order(OrderStatus status) {
        return new OrderRepository.OrderRow(
                7L, "order-1", "customer-1",
                "11111111111111111111111111",
                "22222222222222222222222222",
                new BigDecimal("40.00"), "PLN", status,
                "payment-1", "auth-1", "state-1", "bank-ref", null,
                List.of(
                        item(101L, 1),
                        item(101L, 2),
                        item(102L, 1)
                )
        );
    }

    private static OrderItemResponse item(long productId, int quantity) {
        return new OrderItemResponse(
                productId, "SKU-" + productId, "Product " + productId,
                BigDecimal.TEN, quantity, BigDecimal.TEN.multiply(BigDecimal.valueOf(quantity))
        );
    }
}
