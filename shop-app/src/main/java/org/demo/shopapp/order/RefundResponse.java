package org.demo.shopapp.order;

import java.math.BigDecimal;

public record RefundResponse(
        Long orderId,
        String orderNumber,
        String status,
        BigDecimal amount,
        String currency,
        String bankReference,
        String failureReason,
        boolean replayed
) {

    static RefundResponse from(RefundRepository.RefundRow row, boolean replayed) {
        return new RefundResponse(
                row.orderId(),
                row.orderNumber(),
                row.status(),
                row.amount(),
                row.currency(),
                row.bankReference(),
                row.failureReason(),
                replayed
        );
    }
}
