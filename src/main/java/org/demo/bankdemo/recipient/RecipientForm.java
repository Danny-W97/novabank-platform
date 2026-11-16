package org.demo.bankdemo.recipient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RecipientForm {

    @NotBlank(message = "Podaj alias odbiorcy.")
    @Size(max = 100, message = "Alias może mieć maksymalnie 100 znaków.")
    private String alias;

    @NotBlank(message = "Podaj nazwę odbiorcy.")
    @Size(max = 201, message = "Nazwa może mieć maksymalnie 201 znaków.")
    private String recipientName;

    @NotBlank(message = "Podaj numer rachunku.")
    @Pattern(regexp = "^(?:PL)?[0-9 ]{26,32}$", message = "Podaj 26-cyfrowy numer rachunku.")
    private String accountNumber;

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }
}
