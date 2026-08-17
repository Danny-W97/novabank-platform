package org.demo.bankdemo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
public class TwoFactorLoginService {

    static final String EMAIL_ATTRIBUTE = "BANK_TOTP_LOGIN_EMAIL";
    static final String TARGET_ATTRIBUTE = "BANK_TOTP_LOGIN_TARGET";
    static final String EXPIRES_ATTRIBUTE = "BANK_TOTP_LOGIN_EXPIRES_AT";
    static final String ATTEMPTS_ATTRIBUTE = "BANK_TOTP_LOGIN_ATTEMPTS";
    static final int MAX_ATTEMPTS = 5;
    static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);

    private final DatabaseUserDetailsService userDetailsService;
    private final LoginAttemptService loginAttemptService;
    private final LoginService loginService;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;
    private final LoginHistoryService loginHistoryService;

    public TwoFactorLoginService(
            DatabaseUserDetailsService userDetailsService,
            LoginAttemptService loginAttemptService,
            LoginService loginService,
            SecurityContextRepository securityContextRepository,
            Clock clock,
            LoginHistoryService loginHistoryService
    ) {
        this.userDetailsService = userDetailsService;
        this.loginAttemptService = loginAttemptService;
        this.loginService = loginService;
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
        this.loginHistoryService = loginHistoryService;
    }

    public void start(HttpServletRequest request, String email, String target) {
        var session = request.getSession(true);
        session.setAttribute(EMAIL_ATTRIBUTE, email.strip().toLowerCase(Locale.ROOT));
        session.setAttribute(TARGET_ATTRIBUTE, target);
        session.setAttribute(EXPIRES_ATTRIBUTE, Instant.now(clock)
                .plus(CHALLENGE_LIFETIME).toString());
        session.setAttribute(ATTEMPTS_ATTRIBUTE, 0);
    }

    public Optional<PendingLogin> pending(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }
        var email = (String) session.getAttribute(EMAIL_ATTRIBUTE);
        var target = (String) session.getAttribute(TARGET_ATTRIBUTE);
        var expiresAtValue = (String) session.getAttribute(EXPIRES_ATTRIBUTE);
        if (email == null || target == null || expiresAtValue == null) {
            return Optional.empty();
        }
        try {
            var expiresAt = Instant.parse(expiresAtValue);
            if (!Instant.now(clock).isBefore(expiresAt)) {
                clear(session);
                return Optional.empty();
            }
            var attempts = attempts(session);
            if (attempts >= MAX_ATTEMPTS) {
                clear(session);
                return Optional.empty();
            }
            return Optional.of(new PendingLogin(email, target, attempts, expiresAt));
        } catch (RuntimeException exception) {
            clear(session);
            return Optional.empty();
        }
    }

    public boolean recordFailure(HttpSession session) {
        var pending = pending(session);
        if (pending.isEmpty()) {
            return false;
        }
        var attempts = pending.get().attempts() + 1;
        session.setAttribute(ATTEMPTS_ATTRIBUTE, attempts);
        if (attempts >= MAX_ATTEMPTS) {
            clear(session);
            return false;
        }
        return true;
    }

    public String complete(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session
    ) {
        var pending = pending(session)
                .orElseThrow(() -> new IllegalStateException("Sesja 2FA wygasła."));
        UserDetails userDetails = userDetailsService.loadUserByUsername(pending.email());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                userDetails,
                null,
                userDetails.getAuthorities()
        ));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        clear(session);
        loginAttemptService.clear(pending.email());
        loginService.recordSuccessfulLogin(pending.email());
        loginHistoryService.record(pending.email(), request, true, null);
        return pending.target();
    }

    public void clear(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(EMAIL_ATTRIBUTE);
        session.removeAttribute(TARGET_ATTRIBUTE);
        session.removeAttribute(EXPIRES_ATTRIBUTE);
        session.removeAttribute(ATTEMPTS_ATTRIBUTE);
    }

    private static int attempts(HttpSession session) {
        var value = session.getAttribute(ATTEMPTS_ATTRIBUTE);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public record PendingLogin(String email, String target, int attempts, Instant expiresAt) {
    }
}
