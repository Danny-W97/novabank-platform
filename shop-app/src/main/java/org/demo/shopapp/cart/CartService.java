package org.demo.shopapp.cart;

import org.demo.shopapp.catalog.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    public CartService(CartRepository cartRepository, ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public void addItem(String customerId, long productId, int quantity) {
        productRepository.findActiveById(productId);
        var cartId = cartRepository.ensureCart(customerId);
        cartRepository.addItem(cartId, productId, quantity);
    }

    @Transactional
    public void setItemQuantity(String customerId, long productId, int quantity) {
        productRepository.findActiveById(productId);
        var cartId = cartRepository.ensureCart(customerId);
        cartRepository.setItemQuantity(cartId, productId, quantity);
    }

    public CartResponse getCart(String customerId) {
        return response(customerId, cartRepository.findItems(customerId));
    }

    @Transactional
    public void removeItem(String customerId, long productId) {
        cartRepository.removeItem(customerId, productId);
    }

    @Transactional
    public void clear(String customerId) {
        cartRepository.clear(customerId);
    }

    private static CartResponse response(String customerId, List<CartItemResponse> items) {
        var currency = items.stream()
                .map(CartItemResponse::currency)
                .distinct()
                .reduce((left, right) -> {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Cart cannot contain products in different currencies."
                    );
                })
                .orElse(null);
        var total = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(customerId, items, total, currency);
    }
}
