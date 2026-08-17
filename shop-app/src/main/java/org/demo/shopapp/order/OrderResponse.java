package org.demo.shopapp.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderResponse(
        Long id,
        String orderNumber,
        String status,
        BigDecimal total,
        String currency,
        String authorizationUrl,
        String checkoutUrl,
        String bankReference,
        String failureReason,
        boolean replayed,
        List<OrderItemResponse> items
) {

    public static OrderResponse from(OrderRepository.OrderRow row) {
        return new OrderResponse(
                row.id(),
                row.orderNumber(),
                row.status().name(),
                row.total(),
                row.currency(),
                null,
                row.externalCheckoutUrl(),
                row.bankReference(),
                row.failureReason(),
                false,
                row.items()
        );
    }

    public OrderResponse withReplayed(boolean value) {
        return new OrderResponse(
                id, orderNumber, status, total, currency, authorizationUrl, checkoutUrl,
                bankReference, failureReason, value, items
        );
    }

    public OrderResponse withAuthorizationUrl(String value) {
        return new OrderResponse(
                id, orderNumber, status, total, currency, value,
                checkoutUrl,
                bankReference, failureReason, replayed, items
        );
    }

    public OrderResponse withCheckoutUrl(String value) {
        return new OrderResponse(id, orderNumber, status, total, currency, authorizationUrl, value,
                bankReference, failureReason, replayed, items);
    }
}
