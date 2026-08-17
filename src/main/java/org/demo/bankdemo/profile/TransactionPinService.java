package org.demo.bankdemo.profile;

import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.transfer.TransferException;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class TransactionPinService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    public TransactionPinService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            NotificationService notificationService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public boolean isConfigured(String email) {
        return userFor(email).hasTransactionPin();
    }

    @Transactional
    public void setPin(String email, String currentPassword, String pin) {
        var user = userFor(email);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ProfileException("currentPassword", "Hasło do konta jest nieprawidłowe.");
        }
        user.changeTransactionPin(passwordEncoder.encode(pin));
        notificationService.securityEvent(email, "PIN transakcyjny został zmieniony.");
    }

    @Transactional
    public void removePin(String email, String currentPassword) {
        var user = userFor(email);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ProfileException("currentPassword", "Hasło do konta jest nieprawidłowe.");
        }
        user.changeTransactionPin(null);
        notificationService.securityEvent(email, "PIN transakcyjny został wyłączony.");
    }

    @Transactional(readOnly = true)
    public void verifyForTransfer(String email, String pin) {
        var user = userFor(email);
        if (!user.hasTransactionPin()) {
            return;
        }
        if (pin == null || !passwordEncoder.matches(pin, user.getTransactionPinHash())) {
            throw new TransferException("pin", "Podano zły pin transakcyjny");
        }
    }

    private org.demo.bankdemo.user.User userFor(String email) {
        return userRepository.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono użytkownika."));
    }
}
