package org.demo.bankdemo.transfer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;

public class TransferForm implements Serializable {

    @NotBlank(message = "Podaj nazwę odbiorcy.")
    @Size(max = 201, message = "Nazwa odbiorcy może mieć maksymalnie 201 znaków.")
    private String recipientName;

    @NotBlank(message = "Podaj numer rachunku odbiorcy.")
    @Pattern(
            regexp = "^(?i:PL)?[0-9\\s]+$",
            message = "Numer rachunku może zawierać wyłącznie cyfry, spacje i opcjonalny prefiks PL."
    )
    private String destinationAccountNumber;

    @NotBlank(message = "Podaj kwotę przelewu.")
    @Pattern(
            regexp = "^\\s*\\d{1,17}(?:[,.]\\d{1,2})?\\s*$",
            message = "Podaj dodatnią kwotę z maksymalnie dwoma miejscami po przecinku."
    )
    private String amount;

    @NotBlank(message = "Podaj tytuł przelewu.")
    @Size(max = 140, message = "Tytuł przelewu może mieć maksymalnie 140 znaków.")
    private String title;

    @NotBlank(message = "Brak klucza operacji. Odśwież formularz.")
    @Pattern(
            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
            message = "Nieprawidłowy klucz operacji. Odśwież formularz."
    )
    private String idempotencyKey;

    public TransferCommand toCommand() {
        return new TransferCommand(
                idempotencyKey,
                normalizeText(recipientName),
                normalizeAccountNumber(destinationAccountNumber),
                parseAmount(amount),
                normalizeText(title)
        );
    }

    public static TransferForm from(TransferCommand command) {
        var form = new TransferForm();
        form.setIdempotencyKey(command.idempotencyKey());
        form.setRecipientName(command.recipientName());
        form.setDestinationAccountNumber(command.destinationAccountNumber());
        form.setAmount(command.amount().toPlainString());
        form.setTitle(command.title());
        return form;
    }

    static String normalizeAccountNumber(String value) {
        if (value == null) {
            return "";
        }
        var normalized = value.replaceAll("\\s+", "").toUpperCase();
        return normalized.startsWith("PL") ? normalized.substring(2) : normalized;
    }

    private static BigDecimal parseAmount(String value) {
        return new BigDecimal(value.strip().replace(',', '.')).setScale(2);
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getDestinationAccountNumber() {
        return destinationAccountNumber;
    }

    public void setDestinationAccountNumber(String destinationAccountNumber) {
        this.destinationAccountNumber = destinationAccountNumber;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
