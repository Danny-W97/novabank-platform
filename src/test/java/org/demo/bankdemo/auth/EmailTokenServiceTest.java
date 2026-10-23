package org.demo.bankdemo.auth;

import org.demo.bankdemo.profile.UserSessionService;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailTokenServiceTest {
    private final EmailVerificationTokenRepository verification = mock(EmailVerificationTokenRepository.class);
    private final PasswordResetTokenRepository resets = mock(PasswordResetTokenRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final UserSessionService sessions = mock(UserSessionService.class);
    private final Instant now = Instant.parse("2026-10-16T10:00:00Z");
    private final EmailTokenService service = new EmailTokenService(verification, resets,
            mock(UserRepository.class), encoder, mock(EmailDeliveryService.class), sessions,
            Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void failedAtomicClaimDoesNotChangePasswordOrInvalidateSessions() {
        var user = mock(User.class);
        var token = new PasswordResetToken(user, "test-hash", now.plusSeconds(60));
        when(resets.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(resets.consumeUnused(anyString(), eq(now))).thenReturn(0);
        assertThatThrownBy(() -> service.resetPassword("synthetic-token", "new-password"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(encoder, sessions, user);
    }

    @Test
    void passwordChangesOnlyAfterOneRowWasClaimed() {
        var user = mock(User.class);
        when(user.getEmail()).thenReturn("synthetic@example.invalid");
        when(resets.findByTokenHash(anyString())).thenReturn(Optional.of(
                new PasswordResetToken(user, "test-hash", now.plusSeconds(60))));
        when(resets.consumeUnused(anyString(), eq(now))).thenReturn(1);
        when(encoder.encode("new-password")).thenReturn("new-hash");
        service.resetPassword("synthetic-token", "new-password");
        var order = inOrder(resets, encoder, user, sessions);
        order.verify(resets).consumeUnused(anyString(), eq(now));
        order.verify(encoder).encode("new-password");
        order.verify(user).changePassword("new-hash", now);
        order.verify(sessions).invalidateAllSessions("synthetic@example.invalid");
    }

    @Test
    void failedEmailClaimDoesNotVerifyUser() {
        var user = mock(User.class);
        when(verification.findByTokenHash(anyString())).thenReturn(Optional.of(
                new EmailVerificationToken(user, "test-hash", now.plusSeconds(60))));
        when(verification.consumeUnused(anyString(), eq(now))).thenReturn(0);
        assertThatThrownBy(() -> service.verifyEmail("synthetic-token"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(user);
    }
}
