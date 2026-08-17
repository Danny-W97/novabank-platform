package org.demo.bankdemo.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ScheduledTransferRepository extends JpaRepository<ScheduledTransfer, Long> {

    @Query("select s from ScheduledTransfer s join fetch s.user "
            + "where s.active = true and s.nextExecutionAt <= :now")
    List<ScheduledTransfer> findDue(@Param("now") Instant now);

    List<ScheduledTransfer> findByUserEmailOrderByNextExecutionAtAsc(String email);

    java.util.Optional<ScheduledTransfer> findByIdAndUserEmail(Long id, String email);
}
