package org.demo.bankdemo.currency;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExchangeTransactionRepository extends JpaRepository<ExchangeTransaction, Long> {

    Optional<ExchangeTransaction> findByIdempotencyKey(String idempotencyKey);

    List<ExchangeTransaction> findByAccountIdOrderByCreatedAtDesc(Long accountId, PageRequest pageRequest);
}
