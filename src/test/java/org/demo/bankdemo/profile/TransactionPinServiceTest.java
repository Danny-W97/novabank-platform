package org.demo.bankdemo.profile;

import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.transfer.TransferException;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionPinServiceTest {

    private static final String EMAIL = "anna.nowak@example.pl";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private NotificationService notificationService;

    private TransactionPinService service;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User(EMAIL, "password-hash", "Anna", "Nowak", null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        service = new TransactionPinService(
                userRepository,
                passwordEncoder,
                notificationService
        );
    }

    @Test
    void storesOnlyEncodedPinAfterPasswordVerification() {
        when(passwordEncoder.matches("Password1", "password-hash")).thenReturn(true);
        when(passwordEncoder.encode("1234")).thenReturn("pin-bcrypt");

        service.setPin(EMAIL, "Password1", "1234");

        assertThat(user.getTransactionPinHash()).isEqualTo("pin-bcrypt");
        assertThat(user.getTransactionPinHash()).doesNotContain("1234");
        verify(notificationService).securityEvent(EMAIL, "PIN transakcyjny został zmieniony.");
    }

    @Test
    void rejectsPinChangeWhenAccountPasswordIsWrong() {
        when(passwordEncoder.matches("Wrong", "password-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.setPin(EMAIL, "Wrong", "1234"))
                .isInstanceOf(ProfileException.class);

        assertThat(user.hasTransactionPin()).isFalse();
        verify(passwordEncoder, never()).encode("1234");
    }

    @Test
    void rejectsTransferWhenConfiguredPinDoesNotMatch() {
        user.changeTransactionPin("pin-bcrypt");
        when(passwordEncoder.matches("9999", "pin-bcrypt")).thenReturn(false);

        assertThatThrownBy(() -> service.verifyForTransfer(EMAIL, "9999"))
                .isInstanceOf(TransferException.class)
                .hasMessage("Podano zły pin transakcyjny");
    }
}
