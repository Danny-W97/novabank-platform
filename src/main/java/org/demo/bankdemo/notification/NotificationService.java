package org.demo.bankdemo.notification;

import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class NotificationService {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
                    .withZone(ZoneId.of("Europe/Warsaw"));

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(
            NotificationRepository notificationRepository,
            UserRepository userRepository
    ) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public NotificationPage notificationsFor(String email) {
        var normalizedEmail = normalizeEmail(email);
        var notifications = notificationRepository
                .findByUserEmailOrderByCreatedAtDesc(normalizedEmail, PageRequest.of(0, 100))
                .stream()
                .map(notification -> new NotificationView(
                        notification.getId(),
                        notification.getType().name().toLowerCase(Locale.ROOT),
                        notification.getMessage(),
                        notification.isRead(),
                        DATE_TIME.format(notification.getCreatedAt())
                ))
                .toList();
        return new NotificationPage(
                notifications,
                notificationRepository.countByUserEmailAndReadFalse(normalizedEmail)
        );
    }

    @Transactional
    public void markRead(String email, Long notificationId) {
        notificationRepository.findByIdAndUserEmail(notificationId, normalizeEmail(email))
                .ifPresent(Notification::markRead);
    }

    @Transactional
    public void transferCompleted(
            User sender,
            User recipient,
            String formattedAmount,
            String referenceNumber
    ) {
        notificationRepository.save(new Notification(
                sender,
                NotificationType.SUCCESS,
                "Przelew " + formattedAmount + " został wykonany. Ref. " + referenceNumber
        ));
        notificationRepository.save(new Notification(
                recipient,
                NotificationType.INFO,
                "Otrzymano przelew " + formattedAmount + ". Ref. " + referenceNumber
        ));
    }

    @Transactional
    public void paymentCompleted(
            User customer,
            User merchant,
            String formattedAmount,
            String referenceNumber
    ) {
        notificationRepository.save(new Notification(
                customer,
                NotificationType.SUCCESS,
                "Platnosc " + formattedAmount + " zostala wykonana. Ref. " + referenceNumber
        ));
        notificationRepository.save(new Notification(
                merchant,
                NotificationType.INFO,
                "Otrzymano platnosc " + formattedAmount + ". Ref. " + referenceNumber
        ));
    }

    @Transactional
    public void securityEvent(String email, String message) {
        userRepository.findByEmail(normalizeEmail(email))
                .ifPresent(user -> notificationRepository.save(new Notification(
                        user,
                        NotificationType.SECURITY,
                        message
                )));
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    public record NotificationPage(List<NotificationView> notifications, long unreadCount) {
    }

    public record NotificationView(
            Long id,
            String type,
            String message,
            boolean read,
            String createdAt
    ) {
    }
}
