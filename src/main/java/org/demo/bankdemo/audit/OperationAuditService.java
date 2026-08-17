package org.demo.bankdemo.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class OperationAuditService {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm:ss", Locale.forLanguageTag("pl-PL"))
                    .withZone(ZoneId.of("Europe/Warsaw"));

    private final OperationAuditRepository auditRepository;

    public OperationAuditService(OperationAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String userEmail, String operation, String reason) {
        auditRepository.save(new OperationAudit(
                normalizeNullableEmail(userEmail),
                safe(operation, 40),
                safe(reason, 300)
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String userEmail, String operation, String reason) {
        auditRepository.save(new OperationAudit(
                normalizeNullableEmail(userEmail),
                safe(operation, 40),
                safe(reason, 300),
                null,
                null,
                null,
                true,
                false
        ));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAdminAction(
            String adminEmail,
            String operation,
            String targetUserEmail,
            Long targetAccountId,
            String details,
            boolean success,
            String ipAddress
    ) {
        auditRepository.save(new OperationAudit(
                normalizeNullableEmail(adminEmail),
                safe(operation, 40),
                safe(details, 300),
                normalizeNullableEmail(targetUserEmail),
                targetAccountId,
                safeNullable(ipAddress, 64),
                success,
                true
        ));
    }

    @Transactional(readOnly = true)
    public List<AuditView> recentFailures() {
        return auditRepository.findBySuccessFalseAndAdminActionFalseOrderByCreatedAtDesc(PageRequest.of(0, 100))
                .stream()
                .map(audit -> new AuditView(
                        audit.getId(),
                        audit.getUserEmail(),
                        audit.getOperation(),
                        audit.getReason(),
                        DATE_TIME.format(audit.getCreatedAt())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AdminAuditView> recentAdminActions() {
        return auditRepository.findByAdminActionTrueOrderByCreatedAtDesc(PageRequest.of(0, 100))
                .stream()
                .map(audit -> new AdminAuditView(
                        audit.getId(),
                        audit.getUserEmail(),
                        audit.getOperation(),
                        audit.getTargetUserEmail(),
                        audit.getTargetAccountId(),
                        audit.getReason(),
                        audit.isSuccess(),
                        audit.getIpAddress(),
                        DATE_TIME.format(audit.getCreatedAt())
                ))
                .toList();
    }

    private static String normalizeNullableEmail(String email) {
        return email == null || email.isBlank()
                ? null
                : email.strip().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value, int maxLength) {
        var safeValue = value == null || value.isBlank() ? "Nieznany błąd" : value.strip();
        return safeValue.substring(0, Math.min(maxLength, safeValue.length()));
    }

    private static String safeNullable(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().substring(0, Math.min(maxLength, value.strip().length()));
    }

    public record AuditView(
            Long id,
            String userEmail,
            String operation,
            String reason,
            String createdAt
    ) {
    }

    public record AdminAuditView(
            Long id,
            String adminEmail,
            String operation,
            String targetUserEmail,
            Long targetAccountId,
            String details,
            boolean success,
            String ipAddress,
            String createdAt
    ) {
    }
}
