package org.demo.bankdemo.history;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.demo.bankdemo.transaction.TransactionType;
import org.demo.bankdemo.currency.CurrencyCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

public class HistoryFilter {

    @Size(max = 140, message = "Wyszukiwana fraza może mieć maksymalnie 140 znaków.")
    private String query;

    private HistoryDirection direction = HistoryDirection.ALL;

    private TransactionType type;

    private CurrencyCode currency;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;

    @DecimalMin(value = "0.01", message = "Minimalna kwota musi być większa od zera.")
    @Digits(integer = 17, fraction = 2, message = "Minimalna kwota może mieć maksymalnie dwa miejsca po przecinku.")
    private BigDecimal minAmount;

    @DecimalMin(value = "0.01", message = "Maksymalna kwota musi być większa od zera.")
    @Digits(integer = 17, fraction = 2, message = "Maksymalna kwota może mieć maksymalnie dwa miejsca po przecinku.")
    private BigDecimal maxAmount;

    @Min(value = 0, message = "Numer strony nie może być ujemny.")
    @Max(value = 100000, message = "Numer strony jest zbyt duży.")
    private int page;

    @AssertTrue(message = "Data początkowa nie może być późniejsza niż data końcowa.")
    public boolean isDateRangeValid() {
        return dateFrom == null || dateTo == null || !dateFrom.isAfter(dateTo);
    }

    @AssertTrue(message = "Minimalna kwota nie może być większa niż maksymalna.")
    public boolean isAmountRangeValid() {
        return minAmount == null || maxAmount == null || minAmount.compareTo(maxAmount) <= 0;
    }

    public boolean hasActiveFilters() {
        return normalizedQuery() != null
                || normalizedDirection() != HistoryDirection.ALL
                || type != null
                || currency != null
                || dateFrom != null
                || dateTo != null
                || minAmount != null
                || maxAmount != null;
    }

    public String normalizedQuery() {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.strip().toLowerCase();
    }

    public HistoryDirection normalizedDirection() {
        return direction == null ? HistoryDirection.ALL : direction;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public HistoryDirection getDirection() {
        return direction;
    }

    public void setDirection(HistoryDirection direction) {
        this.direction = direction;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public void setCurrency(CurrencyCode currency) {
        this.currency = currency;
    }

    public LocalDate getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(LocalDate dateFrom) {
        this.dateFrom = dateFrom;
    }

    public LocalDate getDateTo() {
        return dateTo;
    }

    public void setDateTo(LocalDate dateTo) {
        this.dateTo = dateTo;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public void setMinAmount(BigDecimal minAmount) {
        this.minAmount = minAmount;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public void setMaxAmount(BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }
}
