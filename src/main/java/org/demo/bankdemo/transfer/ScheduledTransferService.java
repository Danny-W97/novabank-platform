package org.demo.bankdemo.transfer;

import org.demo.bankdemo.user.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ScheduledTransferService {

    private final ScheduledTransferRepository repository;
    private final UserRepository userRepository;
    private final TransferService transferService;
    private final Clock clock;

    public ScheduledTransferService(
            ScheduledTransferRepository repository,
            UserRepository userRepository,
            TransferService transferService,
            Clock clock
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.transferService = transferService;
        this.clock = clock;
    }

    @Transactional
    public void create(
            String email,
            String destinationAccountNumber,
            String recipientName,
            java.math.BigDecimal amount,
            String title,
            ScheduleFrequency frequency,
            Instant firstExecution
    ) {
        var user = userRepository.findByEmail(normalize(email))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono użytkownika."));
        var command = new TransferCommand(
                UUID.randomUUID().toString(),
                recipientName,
                destinationAccountNumber,
                amount,
                title
        );
        transferService.preview(email, command);
        repository.save(new ScheduledTransfer(
                user,
                destinationAccountNumber,
                recipientName,
                amount,
                title,
                frequency,
                firstExecution
        ));
    }

    @Transactional(readOnly = true)
    public List<ScheduledTransfer> forUser(String email) {
        return repository.findByUserEmailOrderByNextExecutionAtAsc(normalize(email));
    }

    @Transactional
    public void cancel(String email, Long id) {
        repository.findByIdAndUserEmail(id, normalize(email))
                .orElseThrow(() -> new IllegalArgumentException("Nie znaleziono przelewu zaplanowanego."))
                .cancel();
    }

    @Scheduled(fixedDelayString = "${app.transfer.scheduler-delay-ms:60000}")
    @Transactional
    public void executeDue() {
        var now = Instant.now(clock);
        for (var scheduled : repository.findDue(now)) {
            var executionKey = UUID.nameUUIDFromBytes(
                    (scheduled.getId() + ":" + scheduled.getNextExecutionAt()).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ).toString();
            var command = new TransferCommand(
                    executionKey,
                    scheduled.getRecipientName(),
                    scheduled.getDestinationAccountNumber(),
                    scheduled.getAmount(),
                    scheduled.getTitle()
            );
            try {
                transferService.execute(scheduled.getUser().getEmail(), command);
                scheduled.markExecuted(now, nextExecution(scheduled.getNextExecutionAt(), scheduled.getFrequency()));
            } catch (RuntimeException exception) {
                scheduled.markFailed(exception.getMessage(), now.plus(Duration.ofHours(1)));
            }
        }
    }

    private static Instant nextExecution(Instant current, ScheduleFrequency frequency) {
        return switch (frequency) {
            case ONCE -> current;
            case DAILY -> current.plus(Duration.ofDays(1));
            case WEEKLY -> current.plus(Duration.ofDays(7));
            case MONTHLY -> current.atZone(java.time.ZoneOffset.UTC)
                    .plusMonths(1)
                    .with(TemporalAdjusters.firstDayOfMonth())
                    .toInstant();
        };
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
