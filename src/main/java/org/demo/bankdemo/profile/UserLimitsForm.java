package org.demo.bankdemo.profile;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public class UserLimitsForm {

    @NotNull(message = "Podaj limit pojedynczego przelewu.")
    @DecimalMin(value = "0.01", message = "Limit musi byc wiekszy od zera.")
    @Digits(integer = 17, fraction = 2, message = "Limit moze miec maksymalnie dwa miejsca po przecinku.")
    private BigDecimal transferSingleLimit;

    @NotNull(message = "Podaj dzienny limit przelewow.")
    @DecimalMin(value = "0.01", message = "Limit musi byc wiekszy od zera.")
    @Digits(integer = 17, fraction = 2, message = "Limit moze miec maksymalnie dwa miejsca po przecinku.")
    private BigDecimal transferDailyLimit;

    @NotNull(message = "Podaj limit pojedynczej platnosci.")
    @DecimalMin(value = "0.01", message = "Limit musi byc wiekszy od zera.")
    @Digits(integer = 17, fraction = 2, message = "Limit moze miec maksymalnie dwa miejsca po przecinku.")
    private BigDecimal paymentSingleLimit;

    @NotNull(message = "Podaj dzienny limit platnosci.")
    @DecimalMin(value = "0.01", message = "Limit musi byc wiekszy od zera.")
    @Digits(integer = 17, fraction = 2, message = "Limit moze miec maksymalnie dwa miejsca po przecinku.")
    private BigDecimal paymentDailyLimit;

    @NotNull(message = "Podaj dzienny limit karty.")
    @DecimalMin(value = "0.01", message = "Limit musi byc wiekszy od zera.")
    @Digits(integer = 17, fraction = 2, message = "Limit moze miec maksymalnie dwa miejsca po przecinku.")
    private BigDecimal cardDailyLimit;

    @NotNull(message = "Podaj prog wymagania kodu TOTP.")
    @DecimalMin(value = "0.01", message = "Prog musi byc wiekszy od zera.")
    @Digits(integer = 17, fraction = 2, message = "Prog moze miec maksymalnie dwa miejsca po przecinku.")
    private BigDecimal totpRequiredAbove;

    @NotBlank(message = "Podaj haslo do konta.")
    private String currentPassword;

    @NotBlank(message = "Podaj kod TOTP.")
    @Pattern(regexp = "^[0-9]{6}$", message = "Kod TOTP musi skladac sie z 6 cyfr.")
    private String totpCode;

    public BigDecimal getTransferSingleLimit() { return transferSingleLimit; }
    public void setTransferSingleLimit(BigDecimal value) { this.transferSingleLimit = value; }
    public BigDecimal getTransferDailyLimit() { return transferDailyLimit; }
    public void setTransferDailyLimit(BigDecimal value) { this.transferDailyLimit = value; }
    public BigDecimal getPaymentSingleLimit() { return paymentSingleLimit; }
    public void setPaymentSingleLimit(BigDecimal value) { this.paymentSingleLimit = value; }
    public BigDecimal getPaymentDailyLimit() { return paymentDailyLimit; }
    public void setPaymentDailyLimit(BigDecimal value) { this.paymentDailyLimit = value; }
    public BigDecimal getCardDailyLimit() { return cardDailyLimit; }
    public void setCardDailyLimit(BigDecimal value) { this.cardDailyLimit = value; }
    public BigDecimal getTotpRequiredAbove() { return totpRequiredAbove; }
    public void setTotpRequiredAbove(BigDecimal value) { this.totpRequiredAbove = value; }
    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String value) { this.currentPassword = value; }
    public String getTotpCode() { return totpCode; }
    public void setTotpCode(String value) { this.totpCode = value; }
}
