package org.demo.bankdemo.currency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CurrencyBalanceRepository extends JpaRepository<CurrencyBalance, Long> {

    List<CurrencyBalance> findAllByAccountIdOrderByCurrencyAsc(Long accountId);

    Optional<CurrencyBalance> findByAccountIdAndCurrency(Long accountId, CurrencyCode currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select balance from CurrencyBalance balance
            where balance.account.id = :accountId and balance.currency = :currency
            """)
    Optional<CurrencyBalance> findByAccountIdAndCurrencyForUpdate(
            @Param("accountId") Long accountId,
            @Param("currency") CurrencyCode currency
    );
}
