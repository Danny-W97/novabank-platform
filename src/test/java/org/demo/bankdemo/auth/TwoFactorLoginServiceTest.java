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
    void preservesPaymentTargetAndExpiresAfterFiveFailedAttempts() {
        var detailsService = mock(DatabaseUserDetailsService.class);
        var loginAttemptService = mock(LoginAttemptService.class);
        var loginService = mock(LoginService.class);
        var securityContextRepository = mock(SecurityContextRepository.class);
        var loginHistoryService = mock(LoginHistoryService.class);
        var service = new TwoFactorLoginService(
                detailsService,
                loginAttemptService,
                loginService,
                securityContextRepository,
                Clock.fixed(Instant.parse("2026-08-02T21:00:00Z"), ZoneOffset.UTC),
                loginHistoryService
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
        assertThat(service.pending(session)).isEmpty();
    }
}
