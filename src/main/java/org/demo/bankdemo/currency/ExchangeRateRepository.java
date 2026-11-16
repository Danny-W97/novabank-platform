package org.demo.bankdemo.currency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, CurrencyCode> {

    List<ExchangeRate> findAllByOrderByCurrencyAsc();
}
