package org.demo.bankdemo.auth;

import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwoFactorLoginServiceTest {

    @Test
    void disabledUserCannotCompleteAnOtherwiseValidChallenge() {
        var details = mock(DatabaseUserDetailsService.class);
        var challenges = mock(LoginChallengeStore.class);
        var contexts = mock(SecurityContextRepository.class);
        var now = Instant.parse("2026-08-02T21:00:00Z");
        var pending = new TwoFactorLoginService.PendingLogin("challenge", "synthetic@example.invalid", "/dashboard", 0, now.plusSeconds(300));
        when(challenges.read("challenge", now)).thenReturn(java.util.Optional.of(pending));
        when(details.loadUserByUsername(pending.email())).thenReturn(User.withUsername(pending.email())
                .password("synthetic-hash").roles("USER").disabled(true).build());
        var request = new MockHttpServletRequest();
        var session = request.getSession(true);
        session.setAttribute(TwoFactorLoginService.CHALLENGE_ATTRIBUTE, "challenge");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var service = new TwoFactorLoginService(details, mock(LoginAttemptService.class), mock(LoginService.class),
                contexts, Clock.fixed(now, ZoneOffset.UTC), mock(LoginHistoryService.class), challenges);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.complete(request,response,session,"challenge"))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
        org.mockito.Mockito.verify(challenges,org.mockito.Mockito.never()).take(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        assertThat(session.getAttribute(TwoFactorLoginService.CHALLENGE_ATTRIBUTE)).isNull();
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void preservesPaymentTargetAndExpiresAfterFiveFailedAttempts() {
        var detailsService = mock(DatabaseUserDetailsService.class);
        var loginAttemptService = mock(LoginAttemptService.class);
        var loginService = mock(LoginService.class);
        var securityContextRepository = mock(SecurityContextRepository.class);
        var loginHistoryService = mock(LoginHistoryService.class);
        var challenges = mock(LoginChallengeStore.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            var pending = new TwoFactorLoginService.PendingLogin(invocation.getArgument(0),invocation.getArgument(1),
                    invocation.getArgument(2),0,invocation.getArgument(3));
            when(challenges.read(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                    .thenReturn(java.util.Optional.of(pending));
            when(challenges.failure(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
                    .thenReturn(true,true,true,true,false);
            return null;
        }).when(challenges).start(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        var service = new TwoFactorLoginService(
                detailsService,
                loginAttemptService,
                loginService,
                securityContextRepository,
                Clock.fixed(Instant.parse("2026-08-02T21:00:00Z"), ZoneOffset.UTC),
                loginHistoryService,
                challenges
        );
        var request = new MockHttpServletRequest();
        var session = request.getSession(true);

        service.start(request, "Anna.Nowak@example.pl", "/shop/authorize/auth-1");

        assertThat(service.pending(session)).get().satisfies(pending -> {
            assertThat(pending.email()).isEqualTo("anna.nowak@example.pl");
            assertThat(pending.target()).isEqualTo("/shop/authorize/auth-1");
        });
        assertThat(service.recordFailure(session)).isTrue();
        assertThat(service.recordFailure(session)).isTrue();
        assertThat(service.recordFailure(session)).isTrue();
        assertThat(service.recordFailure(session)).isTrue();
        assertThat(service.recordFailure(session)).isFalse();
        when(challenges.read(org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());
        assertThat(service.pending(session)).isEmpty();
    }
}
