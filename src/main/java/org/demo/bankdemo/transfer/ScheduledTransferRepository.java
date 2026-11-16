package org.demo.bankdemo.transfer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, Long> {

    Optional<ScheduledTransfer> findByIdempotencyKey(String idempotencyKey);

    @Query("select max(s.id) from ScheduledTransfer s where s.active = true and s.nextExecutionAt <= :now")
    Long findDueUpperId(@Param("now") Instant now);

    @Query("select new org.demo.bankdemo.transfer.DueOccurrence(s.id,s.nextExecutionAt) from ScheduledTransfer s "
            + "where s.active = true and s.nextExecutionAt <= :now "
            + "and s.id > :afterId and s.id <= :upperId order by s.id")
    List<DueOccurrence> findDueBatchAfter(@Param("now") Instant now, @Param("afterId") Long afterId,
            @Param("upperId") Long upperId, org.springframework.data.domain.Pageable limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScheduledTransfer s where s.id = :id")
    Optional<ScheduledTransfer> findByIdForUpdate(@Param("id") Long id);

    List<ScheduledTransfer> findByUserEmailOrderByNextExecutionAtAsc(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ScheduledTransfer> findByIdAndUserEmail(Long id, String email);
}
