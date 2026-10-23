package org.demo.bankdemo.currency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface ExchangeQuoteRepository extends JpaRepository<ExchangeQuote, String> {
    @Modifying
    @Query("delete from ExchangeQuote q where q.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
