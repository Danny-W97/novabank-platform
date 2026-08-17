package org.demo.bankdemo.profile;

import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.demo.bankdemo.notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class ProfileService {

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", POLISH);
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", POLISH).withZone(WARSAW);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final NotificationService notificationService;
    private final UserSessionService userSessionService;

    @Autowired
    public ProfileService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            NotificationService notificationService,
            UserSessionService userSessionService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.notificationService = notificationService;
        this.userSessionService = userSessionService;
    }

    public ProfileService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            NotificationService notificationService
    ) {
        this(userRepository, passwordEncoder, clock, notificationService, null);
    }

    public ProfileService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this(userRepository, passwordEncoder, clock, null, null);
    }

    @Transactional(readOnly = true)
    public ProfileView profileFor(String authenticatedEmail) {
        var user = userFor(authenticatedEmail);
        return new ProfileView(
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                displayPhone(user.getPhone()),
                user.getBirthDate() == null
                        ? "Nie podano"
                        : DATE_FORMAT.format(user.getBirthDate()),
                DATE_TIME_FORMAT.format(user.getPasswordChangedAt())
        );
    }

    @Transactional(readOnly = true)
    public ProfileUpdateForm editFormFor(String authenticatedEmail) {
        var user = userFor(authenticatedEmail);
        var form = new ProfileUpdateForm();
        form.setFirstName(user.getFirstName());
        form.setLastName(user.getLastName());
        form.setPhone(user.getPhone());
        form.setBirthDate(user.getBirthDate());
        return form;
    }

    @Transactional
    public void updateProfile(String authenticatedEmail, ProfileUpdateForm form) {
        var user = userFor(authenticatedEmail);
        user.updateProfile(
                normalizeRequiredText(form.getFirstName()),
                normalizeRequiredText(form.getLastName()),
                normalizeOptionalText(form.getPhone()),
                form.getBirthDate()
        );
    }

    @Transactional
    public void changePassword(
            String authenticatedEmail,
            String currentPassword,
            String newPassword
    ) {
        var user = userFor(authenticatedEmail);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ProfileException("currentPassword", "Obecne hasło jest nieprawidłowe.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ProfileException("newPassword", "Nowe hasło musi różnić się od obecnego.");
        }
        user.changePassword(passwordEncoder.encode(newPassword), Instant.now(clock));
        if (userSessionService != null) {
            userSessionService.invalidateAllSessions(user.getEmail());
        }
        if (notificationService != null) {
            notificationService.securityEvent(
                    user.getEmail(),
                    "Haslo do konta zostalo zmienione. Jezeli to nie Ty wykonales te operacje, skontaktuj sie z bankiem."
            );
        }
    }

    private User userFor(String authenticatedEmail) {
        var normalizedEmail = authenticatedEmail.strip().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "Nie znaleziono zalogowanego użytkownika."
                ));
    }

    private static String normalizeRequiredText(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String displayPhone(String phone) {
        return phone == null || phone.isBlank() ? "Nie podano" : phone;
    }
}
