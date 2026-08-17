package org.demo.bankdemo.dashboard;

import org.demo.bankdemo.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

@Service
public class DashboardSecurityService {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter LAST_LOGIN_FORMAT = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.forLanguageTag("pl-PL"))
            .withZone(WARSAW);

    private final UserRepository userRepository;

    public DashboardSecurityService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public DashboardSecurityView forUser(String email) {
        var user = userRepository.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono użytkownika."));
        var lastLogin = user.getLastLoginAt() == null
                ? "Brak danych"
                : LAST_LOGIN_FORMAT.format(user.getLastLoginAt());
        return new DashboardSecurityView(lastLogin, user.isTotpEnabled(), user.hasTransactionPin());
    }

    public record DashboardSecurityView(
            String lastLogin,
            boolean twoFactorEnabled,
            boolean transactionPinConfigured
    ) {
        public static DashboardSecurityView empty() {
            return new DashboardSecurityView("Brak danych", false, false);
        }
    }
}
