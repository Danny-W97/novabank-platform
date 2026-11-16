package org.demo.bankdemo.transfer;

import org.demo.bankdemo.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionTimedOutException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class ScheduledTransferService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledTransferService.class);

    private final ScheduledTransferRepository repository;
    private final UserRepository userRepository;
    private final TransferService transferService;
    private final ScheduledTransferExecutionService executionService;
    private final Clock clock;
    private final int batchSize;

    public ScheduledTransferService(
            ScheduledTransferRepository repository,
            UserRepository userRepository,
            TransferService transferService,
            ScheduledTransferExecutionService executionService,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value("${app.transfer.scheduler-batch-size:100}") int batchSize
    ) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.transferService = transferService;
        this.executionService = executionService;
        this.clock = clock;
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Scheduler batch size must be 1–1000.");
        this.batchSize = batchSize;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public void create(
            String email,
            String idempotencyKey,
            String destinationAccountNumber,
            String recipientName,
            java.math.BigDecimal amount,
            String title,
            ScheduleFrequency frequency,
            Instant firstExecution
    ) {
        var normalizedEmail = normalize(email);
        var key = normalizeKey(idempotencyKey);
        if (existingCreation(normalizedEmail, key, destinationAccountNumber, recipientName, amount,
                title, frequency, firstExecution)) {
            return;
        }
        // Serialize creation for one user, then recheck after a competing request commits.
        var user = userRepository.findByEmailForUpdate(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono użytkownika."));
        if (existingCreation(normalizedEmail, key, destinationAccountNumber, recipientName, amount,
                title, frequency, firstExecution)) {
            return;
        }
        if (firstExecution == null || !firstExecution.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("Data wykonania musi być w przyszłości.");
        }
        var command = new TransferCommand(
                key,
                recipientName,
                destinationAccountNumber,
                amount,
                title
        );
        transferService.preview(email, command);
        repository.saveAndFlush(new ScheduledTransfer(
                user,
                key,
                destinationAccountNumber,
                recipientName,
                amount,
                title,
                frequency,
                firstExecution
        ));
    }

    @Transactional(readOnly = true)
    public boolean isAlreadyCreated(String email, TransferCommand command, ScheduleFrequency frequency,
                                    Instant firstExecution) {
        return existingCreation(normalize(email), normalizeKey(command.idempotencyKey()),
                command.destinationAccountNumber(), command.recipientName(), command.amount(),
                command.title(), frequency, firstExecution);
    }

    private boolean existingCreation(String email, String key, String destination, String recipient,
                                     java.math.BigDecimal amount, String title, ScheduleFrequency frequency,
                                     Instant firstExecution) {
        var existing = repository.findByIdempotencyKey(key);
        if (existing.isEmpty()) return false;
        var scheduled = existing.get();
        if (!scheduled.getUser().getEmail().equals(email)
                || !java.util.Objects.equals(scheduled.getDestinationAccountNumber(), destination)
                || !java.util.Objects.equals(scheduled.getRecipientName(), recipient)
                || amount == null || scheduled.getAmount().compareTo(amount) != 0
                || !java.util.Objects.equals(scheduled.getTitle(), title)
                || scheduled.getFrequency() != frequency
                || !java.util.Objects.equals(scheduled.getFirstExecutionAt(), firstExecution)) {
            throw new TransferException(null, "Klucz operacji został już wykorzystany dla innego zlecenia.");
        }
        return true;
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new TransferException(null, "Brak poprawnego klucza operacji. Otwórz ponownie formularz.");
        }
        return key.strip();
    }

    @Transactional(readOnly = true)
    public List<ScheduledTransfer> forUser(String email) {
        return repository.findByUserEmailOrderByNextExecutionAtAsc(normalize(email));
    }

    @Transactional(timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public void cancel(String email, Long id) {
        repository.findByIdAndUserEmail(id, normalize(email))
                .orElseThrow(() -> new IllegalArgumentException("Nie znaleziono przelewu zaplanowanego."))
                .cancel();
    }

    @Scheduled(fixedDelayString = "${app.transfer.scheduler-delay-ms:60000}")
    public void executeDue() {
        // Keep the loop outside a transaction so each occurrence can commit or roll back independently.
        var now = Instant.now(clock);
        var upperId = repository.findDueUpperId(now);
        if (upperId == null) return;
        var limit = org.springframework.data.domain.PageRequest.of(0, batchSize);
        // Cursor uses immutable ID: changing the next due time must not execute the same schedule twice
        // in this tick. The upper bound excludes schedules created while this run is processing.
        var batch = repository.findDueBatchAfter(now, 0L, upperId, limit);
        while (!batch.isEmpty()) {
          for (var scheduled : batch) {
            var id = scheduled.id();
            var dueAt = scheduled.dueAt();
            try {
                executionService.execute(id, dueAt, now);
            } catch (TransientDataAccessException | TransactionTimedOutException exception) {
                // A busy account is not a business failure. Retry the same occurrence on the next tick.
                LOGGER.info("Scheduled transfer {} postponed due to a transient database conflict", id);
            } catch (RuntimeException exception) {
                LOGGER.warn("Scheduled transfer {} failed", id, exception);
                try {
                    executionService.recordFailure(id, dueAt, exception.getMessage(), now.plus(Duration.ofHours(1)));
                } catch (RuntimeException recordingFailure) {
                    LOGGER.error("Could not record failure for scheduled transfer {}", id, recordingFailure);
                }
            }
          }
          if (batch.size() < batchSize) break;
          var cursor = batch.getLast();
          batch = repository.findDueBatchAfter(now, cursor.id(), upperId, limit);
        }
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
