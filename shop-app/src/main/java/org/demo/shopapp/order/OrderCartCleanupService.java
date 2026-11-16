package org.demo.shopapp.order;

import org.demo.shopapp.cart.CartService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;

@Service
public class OrderCartCleanupService {

    private final OrderRepository orderRepository;
    private final CartService cartService;

    public OrderCartCleanupService(OrderRepository orderRepository, CartService cartService) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
    }

    @Transactional
    public void cleanupPaidOrder(OrderRepository.OrderRow order) {
        if (order.status() != OrderStatus.PAID || !orderRepository.claimCartCleanup(order.id())) {
            return;
        }
        // Late success must never remove items from an explicitly independent/new cart.
        if(!orderRepository.ownsCurrentCart(order.id(),order.customerId()))return;

        var purchasedQuantities = new LinkedHashMap<Long, Integer>();
        order.items().forEach(item -> purchasedQuantities.merge(
                item.productId(), item.quantity(), Integer::sum
        ));
        cartService.removePurchasedQuantities(order.customerId(), purchasedQuantities);
        orderRepository.finishPurchase(order.id(),order.customerId());
    }
}
