package org.demo.bankdemo.profile;

import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.demo.bankdemo.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T14:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserSessionService userSessionService;

    private ProfileService profileService;
    private User user;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(
                userRepository,
                passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                notificationService,
                userSessionService
        );
        user = new User(
                "anna.nowak@example.pl",
                "old-hash",
                "Anna",
                "Nowak",
                null
        );
        when(userRepository.findByEmail("anna.nowak@example.pl"))
                .thenReturn(Optional.of(user));
    }

    @Test
    void updatesOnlyEditableProfileFieldsAndNormalizesWhitespace() {
        var form = new ProfileUpdateForm();
        form.setFirstName("  Anna   Maria ");
        form.setLastName(" Nowak-Kowalska ");
        form.setPhone("  +48 500 600 700  ");
        form.setBirthDate(LocalDate.of(1995, 4, 12));

        profileService.updateProfile(" ANNA.NOWAK@EXAMPLE.PL ", form);

        assertThat(user.getFirstName()).isEqualTo("Anna Maria");
        assertThat(user.getLastName()).isEqualTo("Nowak-Kowalska");
        assertThat(user.getPhone()).isEqualTo("+48 500 600 700");
        assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1995, 4, 12));
        assertThat(user.getEmail()).isEqualTo("anna.nowak@example.pl");
    }

    @Test
    void changesPasswordAfterVerifyingCurrentPassword() {
        when(passwordEncoder.matches("Current1", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewSecure2", "old-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewSecure2")).thenReturn("new-hash");

        profileService.changePassword(
                "anna.nowak@example.pl",
                "Current1",
                "NewSecure2"
        );

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getPasswordChangedAt()).isEqualTo(NOW);
        verify(passwordEncoder).encode("NewSecure2");
        verify(userSessionService).invalidateAllSessions("anna.nowak@example.pl");
        verify(notificationService).securityEvent(
                "anna.nowak@example.pl",
                "Haslo do konta zostalo zmienione. Jezeli to nie Ty wykonales te operacje, skontaktuj sie z bankiem."
        );
    }

    @Test
    void rejectsIncorrectCurrentPasswordWithoutChangingHash() {
        when(passwordEncoder.matches("Wrong1", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> profileService.changePassword(
                "anna.nowak@example.pl",
                "Wrong1",
                "NewSecure2"
        ))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("nieprawid");

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(passwordEncoder, never()).encode("NewSecure2");
        verify(userSessionService, never()).invalidateAllSessions("anna.nowak@example.pl");
    }

    @Test
    void rejectsReusingCurrentPassword() {
        when(passwordEncoder.matches("Current1", "old-hash")).thenReturn(true);
        when(passwordEncoder.matches("SamePassword1", "old-hash")).thenReturn(true);

        assertThatThrownBy(() -> profileService.changePassword(
                "anna.nowak@example.pl",
                "Current1",
                "SamePassword1"
        ))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("r");

        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
        verify(passwordEncoder, never()).encode("SamePassword1");
        verify(userSessionService, never()).invalidateAllSessions("anna.nowak@example.pl");
    }
}
