package org.demo.shopapp.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopAuthServiceTest {

    @Mock
    private ShopUserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private ShopAuthService authService;

    @Test
    void registrationNormalizesIdentityAndStoresOnlyEncodedPassword() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("correct horse battery staple")).thenReturn("$2a$encoded");

        authService.register(new RegisterRequest(
                " User@Example.com ",
                "correct horse battery staple",
                "correct horse battery staple"
        ));

        verify(passwordEncoder).encode("correct horse battery staple");
        verify(userRepository).create(
                eq("user@example.com"),
                eq("$2a$encoded"),
                eq("user@example.com"));
    }

    @Test
    void duplicateEmailIsRejectedBeforePasswordEncoding() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest(
                "user@example.com", "password", "password"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Email is already registered");
    }
}
