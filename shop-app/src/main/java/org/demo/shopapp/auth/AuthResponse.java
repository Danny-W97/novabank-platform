package org.demo.shopapp.auth;

public record AuthResponse(
        String email,
        String customerId
) {
    public static AuthResponse from(ShopUserDetails user) {
        return new AuthResponse(user.getUsername(), user.getCustomerId());
    }
}
