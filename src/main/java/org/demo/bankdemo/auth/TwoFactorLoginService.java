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
    static final String CHALLENGE_ATTRIBUTE = "BANK_TOTP_LOGIN_CHALLENGE_ID";

    private final DatabaseUserDetailsService userDetailsService;
    private final LoginAttemptService loginAttemptService;
    private final LoginService loginService;
    private final SecurityContextRepository securityContextRepository;
    private final Clock clock;
    private final LoginHistoryService loginHistoryService;
    private final LoginChallengeStore challenges;

    public TwoFactorLoginService(
            DatabaseUserDetailsService userDetailsService,
            LoginAttemptService loginAttemptService,
            LoginService loginService,
            SecurityContextRepository securityContextRepository,
            Clock clock,
            LoginHistoryService loginHistoryService,
            LoginChallengeStore challenges
    ) {
        this.userDetailsService = userDetailsService;
        this.loginAttemptService = loginAttemptService;
        this.loginService = loginService;
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
        this.loginHistoryService = loginHistoryService;
        this.challenges = challenges;
    }

    public void start(HttpServletRequest request, String email, String target) {
        var session = request.getSession(true);
        clear(session);
        var id = java.util.UUID.randomUUID().toString();
        challenges.start(id, email.strip().toLowerCase(Locale.ROOT), target, Instant.now(clock).plus(CHALLENGE_LIFETIME));
        session.setAttribute(CHALLENGE_ATTRIBUTE, id);
        session.setAttribute(EMAIL_ATTRIBUTE, email.strip().toLowerCase(Locale.ROOT));
        session.setAttribute(TARGET_ATTRIBUTE, target);
        session.setAttribute(EXPIRES_ATTRIBUTE, Instant.now(clock)
                .plus(CHALLENGE_LIFETIME).toString());
        session.setAttribute(ATTEMPTS_ATTRIBUTE, 0);
    }

    public Optional<PendingLogin> pending(HttpSession session) {
        return challenges.read(challengeId(session), Instant.now(clock));
    }

    public boolean recordFailure(HttpSession session) {
        var allowed = challenges.failure(challengeId(session), Instant.now(clock));
        if (!allowed) clear(session);
        return allowed;
    }

    public String complete(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            String expectedChallengeId
    ) {
        if (!java.util.Objects.equals(expectedChallengeId, challengeId(session)))
            throw new IllegalStateException("Wyzwanie 2FA zmieniło się.");
        var pending = challenges.read(expectedChallengeId, Instant.now(clock))
                .orElseThrow(() -> new IllegalStateException("Sesja 2FA wygasła."));
        UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(pending.email());
            new org.springframework.security.authentication.AccountStatusUserDetailsChecker().check(userDetails);
        } catch (org.springframework.security.core.AuthenticationException exception) {
            clear(session);
            SecurityContextHolder.clearContext();
            securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
            throw exception;
        }
        // Exactly one concurrent request can consume the shared challenge.
        challenges.take(expectedChallengeId, Instant.now(clock))
                .orElseThrow(() -> new IllegalStateException("Sesja 2FA została już wykorzystana lub wygasła."));
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
        challenges.delete(challengeId(session));
        session.removeAttribute(CHALLENGE_ATTRIBUTE);
        session.removeAttribute(EMAIL_ATTRIBUTE);
        session.removeAttribute(TARGET_ATTRIBUTE);
        session.removeAttribute(EXPIRES_ATTRIBUTE);
        session.removeAttribute(ATTEMPTS_ATTRIBUTE);
    }

    private static String challengeId(HttpSession session) {
        return session == null ? null : (String) session.getAttribute(CHALLENGE_ATTRIBUTE);
    }

    public record PendingLogin(String challengeId, String email, String target, int attempts, Instant expiresAt) {
    }
}
