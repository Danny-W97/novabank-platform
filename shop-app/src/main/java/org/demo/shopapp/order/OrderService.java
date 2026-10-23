package org.demo.shopapp.order;

import org.demo.shopapp.cart.CartItemResponse;
import org.demo.shopapp.cart.CartRepository;
import org.demo.shopapp.config.ShopOrderProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;
    private final ShopOrderProperties properties;
    private final org.demo.shopapp.cart.PurchaseIdentityService purchases;

    public OrderService(
            CartRepository cartRepository,
            OrderRepository orderRepository,
            ShopOrderProperties properties,
            org.demo.shopapp.cart.PurchaseIdentityService purchases
    ) {
        this.cartRepository = cartRepository;
        this.orderRepository = orderRepository;
        this.properties = properties;
        this.purchases=purchases;
    }

    @Transactional
    public OrderResponse create(String customerId) {
        var purchase=purchases.lock(customerId);
        var existing=orderRepository.byPurchase(customerId,purchase);
        if(existing.isPresent())return OrderResponse.from(existing.get());
        if (orderRepository.hasUnresolvedCardPayment(customerId))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Poprzednia płatność wymaga uzgodnienia. Sprawdź istniejące zamówienie w Moich zamówieniach.");
        var merchantAccount = properties.getMerchantAccountNumber();
        if (merchantAccount == null || !merchantAccount.matches("\\d{26}")) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Rachunek odbiorcy sklepu nie jest skonfigurowany. Ustaw SHOP_MERCHANT_ACCOUNT_NUMBER w .novabank/env/dev.env albo w Vault dla profilu prod."
            );
        }
        var items = cartRepository.findItems(customerId);
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cart is empty.");
        }
        var currency = currency(items);
        var total = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var order = orderRepository.create(
                customerId, merchantAccount,
                total, currency, items
        );
        orderRepository.bindPurchase(order.id(),purchase);
        return OrderResponse.from(order);
    }

    public OrderResponse get(long orderId, String customerId) {
        return OrderResponse.from(orderRepository.findById(orderId, customerId));
    }

    public List<OrderResponse> list(String customerId) {
        return orderRepository.findAll(customerId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    private static String currency(List<CartItemResponse> items) {
        var currencies = items.stream().map(CartItemResponse::currency).distinct().toList();
        if (currencies.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Order cannot contain products in different currencies."
            );
        }
        return currencies.getFirst();
    }
}
