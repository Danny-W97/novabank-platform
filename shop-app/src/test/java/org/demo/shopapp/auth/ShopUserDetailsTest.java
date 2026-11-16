package org.demo.shopapp.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopUserDetailsTest {

    @Test
    void activeUserHasCustomerAuthority() {
        var user = new ShopUserDetails(
                7L,
                "user@example.com",
                "{bcrypt}hash",
                "user@example.com",
                true
        );

        assertThat(user.getUsername()).isEqualTo("user@example.com");
        assertThat(user.getCustomerId()).isEqualTo("user@example.com");
        assertThat(user.getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_CUSTOMER");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
    }

    @Test
    void inactiveUserCannotAuthenticate() {
        var user = new ShopUserDetails(7L, "user@example.com", "hash", "customer", false);

        assertThat(user.isEnabled()).isFalse();
        assertThat(user.isAccountNonLocked()).isFalse();
    }

    @Test
    void adminUserHasAdminAuthority() {
        var user = new ShopUserDetails(
                8L,
                "admin@example.com",
                "hash",
                "admin@example.com",
                true,
                "ADMIN"
        );

        assertThat(user.getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
    }
}
