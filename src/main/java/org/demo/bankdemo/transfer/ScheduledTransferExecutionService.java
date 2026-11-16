package org.demo.bankdemo.transfer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class ScheduledTransferExecutionService {

    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Warsaw");

    private final ScheduledTransferRepository repository;
    private final TransferService transferService;

    public ScheduledTransferExecutionService(
            ScheduledTransferRepository repository,
            TransferService transferService
    ) {
        this.repository = repository;
        this.transferService = transferService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public void execute(Long id, Instant dueAt, Instant now) {
        // Lock and recheck the occurrence before creating a transfer; another scheduler may have handled it.
        var scheduled = currentOccurrence(id, dueAt);
        if (scheduled == null || scheduled.getNextExecutionAt().isAfter(now)) {
            return;
        }

        var executionKey = UUID.nameUUIDFromBytes(
                (id + ":" + scheduled.getScheduledForAt()).getBytes(StandardCharsets.UTF_8)
        ).toString();
        var command = new TransferCommand(
                executionKey,
                scheduled.getRecipientName(),
                scheduled.getDestinationAccountNumber(),
                scheduled.getAmount(),
                scheduled.getTitle()
        );
        transferService.execute(scheduled.getUser().getEmail(), command);
        scheduled.markExecuted(now, nextExecution(scheduled.getScheduledForAt(), scheduled.getFrequency(),
                scheduled.getExecutionDayOfMonth()));
    }

    @Transactional(timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public void recordFailure(Long id, Instant dueAt, String error, Instant retryAt) {
        // Called only after execute has rolled back, in a fresh transaction.
        var scheduled = currentOccurrence(id, dueAt);
        if (scheduled != null) {
            scheduled.markFailed(error, retryAt);
        }
    }

    private ScheduledTransfer currentOccurrence(Long id, Instant dueAt) {
        return repository.findByIdForUpdate(id)
                .filter(scheduled -> scheduled.isActive()
                        && scheduled.getNextExecutionAt().equals(dueAt))
                .orElse(null);
    }

    private static Instant nextExecution(Instant current, ScheduleFrequency frequency, Integer dayOfMonth) {
        return switch (frequency) {
            case ONCE -> current;
            case DAILY -> current.atZone(BANK_ZONE).plusDays(1).toInstant();
            case WEEKLY -> current.atZone(BANK_ZONE).plusWeeks(1).toInstant();
            case MONTHLY -> {
                var nextMonth = current.atZone(BANK_ZONE).plusMonths(1);
                var desiredDay = dayOfMonth == null ? current.atZone(BANK_ZONE).getDayOfMonth() : dayOfMonth;
                yield nextMonth.withDayOfMonth(Math.min(desiredDay, nextMonth.toLocalDate().lengthOfMonth()))
                        .toInstant();
            }
        };
    }
}
