package org.demo.bankdemo.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.LockModeType;

public interface TransactionRepository extends JpaRepository<BankTransaction, Long>, JpaSpecificationExecutor<BankTransaction> {

    Optional<BankTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<BankTransaction> findByReferenceNumber(String referenceNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from BankTransaction t where t.referenceNumber = :referenceNumber")
    Optional<BankTransaction> findByReferenceNumberForUpdate(@Param("referenceNumber") String referenceNumber);

    Optional<BankTransaction> findByRefundOriginalId(Long originalId);

    @Query("select coalesce(sum(t.amount), 0) from BankTransaction t "
            + "where t.sourceAccount.owner.email = :email "
            + "and t.type in :types and t.status = org.demo.bankdemo.transaction.TransactionStatus.COMPLETED "
            + "and t.createdAt >= :from")
    BigDecimal sumOutgoingByEmailSince(
            @Param("email") String email,
            @Param("types") java.util.Collection<TransactionType> types,
            @Param("from") Instant from
    );
}
