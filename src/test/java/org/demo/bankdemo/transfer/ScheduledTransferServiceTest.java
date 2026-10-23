package org.demo.bankdemo.transfer;

import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.dao.CannotAcquireLockException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduledTransferServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T09:05:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-10-08T09:00:00Z");

    @Mock
    private ScheduledTransferRepository repository;

    @Mock
    private org.demo.bankdemo.user.UserRepository userRepository;

    @Mock
    private TransferService transferService;

    @Mock
    private ScheduledTransferExecutionService executionService;

    private ScheduledTransferService service() {
        return new ScheduledTransferService(
                repository,
                userRepository,
                transferService,
                executionService,
                Clock.fixed(NOW, ZoneOffset.UTC), 100
        );
    }

    @Test
    void failureOfOneOccurrenceDoesNotPreventProcessingAnother() throws Exception {
        when(repository.findDueUpperId(NOW)).thenReturn(3L);
        when(repository.findDueBatchAfter(eq(NOW), eq(0L), eq(3L), any())).thenReturn(List.of(due(1L), due(2L)));
        doThrow(new TransferException("amount", "Brak środków"))
                .when(executionService).execute(1L, DUE_AT, NOW);

        service().executeDue();

        verify(executionService).recordFailure(1L, DUE_AT, "Brak środków", NOW.plusSeconds(60 * 60));
        verify(executionService).execute(2L, DUE_AT, NOW);
        verify(executionService, never()).recordFailure(2L, DUE_AT, "Brak środków", NOW.plusSeconds(60 * 60));
        assertThat(ScheduledTransferService.class.getMethod("executeDue")
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void recordingFailureDoesNotPreventProcessingNextOccurrence() {
        when(repository.findDueUpperId(NOW)).thenReturn(3L);
        when(repository.findDueBatchAfter(eq(NOW), eq(0L), eq(3L), any())).thenReturn(List.of(due(1L), due(2L)));
        doThrow(new TransferException("amount", "Brak środków"))
                .when(executionService).execute(1L, DUE_AT, NOW);
        doThrow(new IllegalStateException("Baza niedostępna"))
                .when(executionService).recordFailure(1L, DUE_AT, "Brak środków", NOW.plusSeconds(60 * 60));

        service().executeDue();

        verify(executionService).execute(2L, DUE_AT, NOW);
    }

    @Test
    void createsOnlyOnceForRepeatedFormEvenAfterScheduleHasExecuted() {
        var saved = new AtomicReference<ScheduledTransfer>();
        var user = new User("sender@example.pl", "hash", "Jan", "Kowalski", null);
        when(userRepository.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(repository.findByIdempotencyKey("creation-1"))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(repository.saveAndFlush(any(ScheduledTransfer.class))).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return saved.get();
        });
        var service = service();
        var first = NOW.plusSeconds(3600);
        service.create(user.getEmail(), "creation-1", "12127943138124663147042900", "Anna Nowak",
                new BigDecimal("125.00"), "Przelew", ScheduleFrequency.DAILY, first);
        saved.get().markExecuted(first, first.plusSeconds(86400));
        service.create(user.getEmail(), "creation-1", "12127943138124663147042900", "Anna Nowak",
                new BigDecimal("125.00"), "Przelew", ScheduleFrequency.DAILY, first);
        verify(repository, times(1)).saveAndFlush(any());
        verify(userRepository, times(1)).findByEmailForUpdate(user.getEmail());
        assertThat(saved.get().getFirstExecutionAt()).isEqualTo(first);
    }

    @Test
    void rechecksCreationKeyAfterUserLockWithoutRepeatingPreview() {
        var existing = scheduled(1L);
        when(repository.findByIdempotencyKey(existing.getIdempotencyKey()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(userRepository.findByEmailForUpdate(existing.getUser().getEmail()))
                .thenReturn(Optional.of(existing.getUser()));
        service().create(existing.getUser().getEmail(), existing.getIdempotencyKey(),
                existing.getDestinationAccountNumber(), existing.getRecipientName(), existing.getAmount(),
                existing.getTitle(), existing.getFrequency(), existing.getFirstExecutionAt());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(transferService);
    }

    @Test
    void rejectsChangedAmountDateAndOwnerUnderExistingCreationKey() {
        var existing = scheduled(1L);
        when(repository.findByIdempotencyKey(existing.getIdempotencyKey())).thenReturn(Optional.of(existing));
        for (int variation = 0; variation < 3; variation++) {
            var email = variation == 0 ? "other@example.pl" : existing.getUser().getEmail();
            var amount = variation == 1 ? new BigDecimal("500.00") : existing.getAmount();
            var date = variation == 2 ? DUE_AT.plusSeconds(60) : DUE_AT;
            assertThatThrownBy(() -> service().create(email, existing.getIdempotencyKey(),
                    existing.getDestinationAccountNumber(), existing.getRecipientName(), amount,
                    existing.getTitle(), existing.getFrequency(), date)).isInstanceOf(TransferException.class);
        }
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void transientLockConflictDoesNotDelayOccurrenceByAnHourOrBlockNextOne() {
        when(repository.findDueUpperId(NOW)).thenReturn(3L);
        when(repository.findDueBatchAfter(eq(NOW), eq(0L), eq(3L), any())).thenReturn(List.of(due(1L), due(2L)));
        doThrow(new CannotAcquireLockException("busy")).when(executionService).execute(1L, DUE_AT, NOW);
        service().executeDue();
        verify(executionService).execute(2L, DUE_AT, NOW);
        verify(executionService, never()).recordFailure(any(), any(), any(), any());
    }

    @Test void cursorAdvancesEvenWhenFirstBatchContainsTransientFailures() {
        when(repository.findDueUpperId(NOW)).thenReturn(3L);
        when(repository.findDueBatchAfter(eq(NOW), eq(0L), eq(3L), any())).thenReturn(List.of(due(1L), due(2L)));
        when(repository.findDueBatchAfter(eq(NOW), eq(2L), eq(3L), any())).thenReturn(List.of(due(3L)));
        doThrow(new CannotAcquireLockException("busy")).when(executionService).execute(1L, DUE_AT, NOW);
        new ScheduledTransferService(repository, userRepository, transferService, executionService,
                Clock.fixed(NOW, ZoneOffset.UTC), 2).executeDue();
        verify(executionService).execute(3L, DUE_AT, NOW);
        verify(executionService, never()).recordFailure(any(), any(), any(), any());
    }

    private static DueOccurrence due(Long id) {
        return new DueOccurrence(id, DUE_AT);
    }

    private static ScheduledTransfer scheduled(Long id) {
        var scheduled = new ScheduledTransfer(new User("sender@example.pl", "hash", "Jan", "Kowalski", null),
                "scheduled-"+id, "12127943138124663147042900", "Anna Nowak", new BigDecimal("125.00"),
                "Przelew cykliczny", ScheduleFrequency.DAILY, DUE_AT);
        ReflectionTestUtils.setField(scheduled, "id", id);
        return scheduled;
    }
}
