package org.demo.shopapp.cart;

import jakarta.validation.Valid;
import org.demo.shopapp.auth.ShopUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    CartResponse get(@AuthenticationPrincipal ShopUserDetails user) {
        return cartService.getCart(user.getCustomerId());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void add(
            @AuthenticationPrincipal ShopUserDetails user,
            @Valid @RequestBody CartItemRequest request
    ) {
        cartService.addItem(user.getCustomerId(), request.productId(), request.quantity());
    }

    @PutMapping("/items/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setQuantity(
            @AuthenticationPrincipal ShopUserDetails user,
            @PathVariable long productId,
            @Valid @RequestBody CartItemRequest request
    ) {
        cartService.setItemQuantity(user.getCustomerId(), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(
            @AuthenticationPrincipal ShopUserDetails user,
            @PathVariable long productId
    ) {
        cartService.removeItem(user.getCustomerId(), productId);
    }
}
