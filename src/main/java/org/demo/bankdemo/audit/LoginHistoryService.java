package org.demo.bankdemo.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class LoginHistoryService {

    public static final String NEW_DEVICE_ATTRIBUTE = "BANK_NEW_DEVICE";

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm:ss", Locale.forLanguageTag("pl-PL"))
                    .withZone(WARSAW);

    private final LoginHistoryRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Autowired
    public LoginHistoryService(
            LoginHistoryRepository repository,
            UserRepository userRepository,
            NotificationService notificationService
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    public LoginHistoryService(LoginHistoryRepository repository, UserRepository userRepository) {
        this(repository, userRepository, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String email, HttpServletRequest request, boolean successful, String reason) {
        var normalizedEmail = normalize(email);
        var ipAddress = clientIp(request);
        var userAgent = limit(request.getHeader("User-Agent"), 500);
        var previousSuccessfulLogins = successful
                ? repository.findTop50ByEmailAndSuccessfulTrueOrderByCreatedAtDesc(normalizedEmail)
                : List.<LoginHistory>of();
        var knownDevice = previousSuccessfulLogins.stream()
                .anyMatch(entry -> Objects.equals(entry.getIpAddress(), ipAddress)
                        && Objects.equals(entry.getUserAgent(), userAgent));
        var userId = userRepository.findByEmail(normalizedEmail)
                .map(user -> user.getId())
                .orElse(null);
        repository.save(new LoginHistory(
                userId,
                normalizedEmail,
                ipAddress,
                userAgent,
                successful,
                limit(reason, 300)
        ));

        if (successful && !previousSuccessfulLogins.isEmpty() && !knownDevice) {
            request.getSession(true).setAttribute(NEW_DEVICE_ATTRIBUTE, true);
            if (notificationService != null) {
                notificationService.securityEvent(
                        normalizedEmail,
                        "Wykryto logowanie z nowego urzadzenia. IP: "
                                + (ipAddress == null ? "nieznane" : ipAddress)
                                + ". Urzadzenie: "
                                + (userAgent == null ? "nieznane" : limit(userAgent, 120))
                                + "."
                );
            }
        }
    }

    @Transactional(readOnly = true)
    public List<LoginView> historyFor(String email) {
        return repository.findTop50ByEmailOrderByCreatedAtDesc(normalize(email))
                .stream()
                .map(entry -> new LoginView(
                        DATE_TIME.format(entry.getCreatedAt()),
                        entry.getIpAddress() == null ? "Nieznany" : entry.getIpAddress(),
                        entry.getUserAgent() == null ? "Nieznane urzadzenie" : entry.getUserAgent(),
                        entry.isSuccessful(),
                        entry.getFailureReason()
                ))
                .toList();
    }

    private static String clientIp(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return limit(forwarded.split(",")[0].strip(), 64);
        }
        return limit(request.getRemoteAddr(), 64);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record LoginView(
            String createdAt,
            String ipAddress,
            String userAgent,
            boolean successful,
            String failureReason
    ) { }
}
