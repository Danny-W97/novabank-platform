package org.demo.bankdemo.transfer;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ScheduledTransferForm {

    @NotBlank(message = "Podaj nazwę odbiorcy.")
    @Size(max = 201, message = "Nazwa odbiorcy może mieć maksymalnie 201 znaków.")
    private String recipientName;

    @NotBlank(message = "Podaj numer rachunku odbiorcy.")
    @Pattern(regexp = "^(?i:PL)?[0-9\\s]+$", message = "Podaj poprawny numer rachunku.")
    private String destinationAccountNumber;

    @NotBlank(message = "Podaj kwotę przelewu.")
    @Pattern(regexp = "^\\s*\\d{1,17}(?:[,.]\\d{1,2})?\\s*$", message = "Podaj poprawną kwotę.")
    private String amount;

    @NotBlank(message = "Podaj tytuł przelewu.")
    @Size(max = 140, message = "Tytuł może mieć maksymalnie 140 znaków.")
    private String title;

    @NotNull(message = "Wybierz częstotliwość.")
    private ScheduleFrequency frequency = ScheduleFrequency.WEEKLY;

    @NotNull(message = "Wybierz datę pierwszego wykonania.")
    @Future(message = "Data wykonania musi być w przyszłości.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime firstExecution = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);

    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getDestinationAccountNumber() { return destinationAccountNumber; }
    public void setDestinationAccountNumber(String destinationAccountNumber) { this.destinationAccountNumber = destinationAccountNumber; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public ScheduleFrequency getFrequency() { return frequency; }
    public void setFrequency(ScheduleFrequency frequency) { this.frequency = frequency; }
    public LocalDateTime getFirstExecution() { return firstExecution; }
    public void setFirstExecution(LocalDateTime firstExecution) { this.firstExecution = firstExecution; }

    public BigDecimal amountValue() {
        return new BigDecimal(amount.strip().replace(',', '.')).setScale(2);
    }

    public String accountValue() {
        var value = destinationAccountNumber.replaceAll("\\s+", "").toUpperCase();
        return value.startsWith("PL") ? value.substring(2) : value;
    }
}
