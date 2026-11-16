package org.demo.bankdemo.transfer;

import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledTransferExecutionServiceTest {

    private static final Long ID = 42L;
    private static final Instant DUE_AT = Instant.parse("2026-10-08T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-08T09:05:00Z");

    @Mock
    private ScheduledTransferRepository repository;

    @Mock
    private TransferService transferService;

    @InjectMocks
    private ScheduledTransferExecutionService service;

    @Test
    void executesAndAdvancesOneOccurrenceUsingStableIdempotencyKey() {
        var scheduled = scheduled(ScheduleFrequency.DAILY);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));

        service.execute(ID, DUE_AT, NOW);

        var command = ArgumentCaptor.forClass(TransferCommand.class);
        verify(transferService).execute(org.mockito.ArgumentMatchers.eq("sender@example.pl"), command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(UUID.nameUUIDFromBytes(
                (ID + ":" + DUE_AT).getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(command.getValue().amount()).isEqualByComparingTo("125.00");
        assertThat(scheduled.getLastExecutedAt()).isEqualTo(NOW);
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(DUE_AT.plusSeconds(24 * 60 * 60));
        assertThat(scheduled.isActive()).isTrue();
    }

    @Test
    void transferFailureDoesNotMarkOccurrenceSuccessfulOrFailedInSameTransaction() {
        var scheduled = scheduled(ScheduleFrequency.ONCE);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));
        when(transferService.execute(any(), any())).thenThrow(new TransferException("amount", "Brak środków"));

        assertThatThrownBy(() -> service.execute(ID, DUE_AT, NOW))
                .isInstanceOf(TransferException.class)
                .hasMessage("Brak środków");

        assertThat(scheduled.getLastExecutedAt()).isNull();
        assertThat(scheduled.getLastError()).isNull();
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(DUE_AT);
        assertThat(scheduled.isActive()).isTrue();
    }

    @Test
    void recordsFailureInSeparateTransactionalMethodAfterFailedExecution() throws Exception {
        var scheduled = scheduled(ScheduleFrequency.ONCE);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));
        var retryAt = NOW.plusSeconds(60 * 60);

        service.recordFailure(ID, DUE_AT, "Brak środków", retryAt);

        assertThat(scheduled.getLastError()).isEqualTo("Brak środków");
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(retryAt);
        assertThat(scheduled.getScheduledForAt()).isEqualTo(DUE_AT);
        assertThat(scheduled.getLastExecutedAt()).isNull();
        assertThat(scheduled.isActive()).isTrue();
        assertThat(ScheduledTransferExecutionService.class.getMethod(
                "recordFailure", Long.class, Instant.class, String.class, Instant.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void retryKeepsOriginalIdempotencyKeyAndDailyWallClockTime() {
        var scheduled = scheduled(ScheduleFrequency.DAILY);
        var retryAt = NOW.plusSeconds(60 * 60);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));

        service.recordFailure(ID, DUE_AT, "Brak środków", retryAt);
        service.execute(ID, retryAt, retryAt.plusSeconds(60));

        var command = ArgumentCaptor.forClass(TransferCommand.class);
        verify(transferService).execute(org.mockito.ArgumentMatchers.eq("sender@example.pl"), command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo(UUID.nameUUIDFromBytes(
                (ID + ":" + DUE_AT).getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(DUE_AT.plusSeconds(24 * 60 * 60));
        assertThat(scheduled.getScheduledForAt()).isEqualTo(DUE_AT.plusSeconds(24 * 60 * 60));
    }

    @Test
    void skipsStaleOccurrenceAfterAnotherWorkerHasAdvancedIt() {
        var scheduled = scheduled(ScheduleFrequency.DAILY);
        scheduled.markExecuted(NOW, DUE_AT.plusSeconds(24 * 60 * 60));
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));

        service.execute(ID, DUE_AT, NOW);
        service.recordFailure(ID, DUE_AT, "Nieaktualny błąd", NOW.plusSeconds(60 * 60));

        verify(transferService, never()).execute(any(), any());
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(DUE_AT.plusSeconds(24 * 60 * 60));
        assertThat(scheduled.getLastError()).isNull();
    }

    @Test
    void oneTimeOccurrenceBecomesInactiveAfterSuccess() {
        var scheduled = scheduled(ScheduleFrequency.ONCE);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));

        service.execute(ID, DUE_AT, NOW);

        assertThat(scheduled.isActive()).isFalse();
        assertThat(scheduled.getLastExecutedAt()).isEqualTo(NOW);
    }

    @Test
    void dailyOccurrenceKeepsPolishWallClockTimeAcrossDst() {
        var dueAt = LocalDateTime.of(2026, 3, 28, 9, 0)
                .atZone(ZoneId.of("Europe/Warsaw")).toInstant();
        var scheduled = scheduledAt(ScheduleFrequency.DAILY, dueAt);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));

        service.execute(ID, dueAt, dueAt.plusSeconds(60));

        assertThat(scheduled.getNextExecutionAt()).isEqualTo(
                LocalDateTime.of(2026, 3, 29, 9, 0)
                        .atZone(ZoneId.of("Europe/Warsaw")).toInstant());
    }

    @Test
    void monthlyOccurrenceReturnsToOriginalDayAfterShorterMonth() {
        var january = LocalDateTime.of(2026, 1, 31, 9, 0)
                .atZone(ZoneId.of("Europe/Warsaw")).toInstant();
        var february = LocalDateTime.of(2026, 2, 28, 9, 0)
                .atZone(ZoneId.of("Europe/Warsaw")).toInstant();
        var march = LocalDateTime.of(2026, 3, 31, 9, 0)
                .atZone(ZoneId.of("Europe/Warsaw")).toInstant();
        var scheduled = scheduledAt(ScheduleFrequency.MONTHLY, january);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));

        service.execute(ID, january, january.plusSeconds(60));
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(february);
        service.execute(ID, february, february.plusSeconds(60));
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(march);
    }

    @Test
    void failureRecorderDoesNotOverwriteCompletedOneTimeOccurrence() {
        var scheduled = scheduled(ScheduleFrequency.ONCE);
        scheduled.markExecuted(NOW, DUE_AT);
        when(repository.findByIdForUpdate(ID)).thenReturn(Optional.of(scheduled));

        service.recordFailure(ID, DUE_AT, "Niepewny wynik COMMIT", NOW.plusSeconds(60 * 60));

        assertThat(scheduled.isActive()).isFalse();
        assertThat(scheduled.getLastExecutedAt()).isEqualTo(NOW);
        assertThat(scheduled.getLastError()).isNull();
        assertThat(scheduled.getNextExecutionAt()).isEqualTo(DUE_AT);
    }

    @Test
    void executionMethodHasItsOwnTransactionBoundary() throws Exception {
        assertThat(ScheduledTransferExecutionService.class.getMethod(
                "execute", Long.class, Instant.class, Instant.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    private static ScheduledTransfer scheduled(ScheduleFrequency frequency) {
        return scheduledAt(frequency, DUE_AT);
    }

    private static ScheduledTransfer scheduledAt(ScheduleFrequency frequency, Instant dueAt) {
        var user = new User("sender@example.pl", "hash", "Jan", "Kowalski", null);
        var scheduled = new ScheduledTransfer(
                user,
                "scheduled-" + ID,
                "12127943138124663147042900",
                "Anna Nowak",
                new BigDecimal("125.00"),
                "Przelew cykliczny",
                frequency,
                dueAt
        );
        ReflectionTestUtils.setField(scheduled, "id", ID);
        return scheduled;
    }
}
