package org.demo.shopapp.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only compatibility facade. Payment writes belong exclusively to PaymentAttemptService. */
@Service
public class OrderStateService {
    private final OrderRepository orders;
    public OrderStateService(OrderRepository orders) { this.orders = orders; }

    @Transactional(readOnly = true)
    public OrderRepository.OrderRow findByOrderNumber(String orderNumber) {
        return orders.findByOrderNumber(orderNumber);
    }
}
