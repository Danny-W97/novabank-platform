package org.demo.bankdemo.card;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;

public interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {

    Optional<CardTransaction> findByIdempotencyKey(String idempotencyKey);

    List<CardTransaction> findByCardIdOrderByCreatedAtDesc(Long cardId, PageRequest pageRequest);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(transaction.amountInPln), 0) from CardTransaction transaction "
                    + "where transaction.card.id = :cardId "
                    + "and transaction.status = org.demo.bankdemo.card.CardTransactionStatus.COMPLETED "
                    + "and transaction.createdAt >= :from and transaction.createdAt < :to"
    )
    BigDecimal sumCompletedAmountInPln(
            @org.springframework.data.repository.query.Param("cardId") Long cardId,
            @org.springframework.data.repository.query.Param("from") Instant from,
            @org.springframework.data.repository.query.Param("to") Instant to
    );
}
