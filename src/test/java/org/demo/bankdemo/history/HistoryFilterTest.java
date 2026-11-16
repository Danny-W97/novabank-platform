package org.demo.bankdemo.history;

import org.demo.bankdemo.currency.CurrencyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryFilterTest {

    @Test
    void detectsInvalidDateAndAmountRanges() {
        var filter = new HistoryFilter();
        filter.setDateFrom(LocalDate.of(2026, 7, 27));
        filter.setDateTo(LocalDate.of(2026, 7, 26));
        filter.setMinAmount(new BigDecimal("200.00"));
        filter.setMaxAmount(new BigDecimal("100.00"));

        assertThat(filter.isDateRangeValid()).isFalse();
        assertThat(filter.isAmountRangeValid()).isFalse();
    }

    @Test
    void recognizesSearchAndCurrencyFilters() {
        var filter = new HistoryFilter();
        filter.setQuery("  TEST ETAPU 3  ");
        filter.setDirection(null);
        filter.setCurrency(CurrencyCode.EUR);

        assertThat(filter.normalizedQuery()).isEqualTo("test etapu 3");
        assertThat(filter.normalizedDirection()).isEqualTo(HistoryDirection.ALL);
        assertThat(filter.hasActiveFilters()).isTrue();
        assertThat(filter.getCurrency()).isEqualTo(CurrencyCode.EUR);
    }
}
