package org.demo.bankdemo.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class TransactionPinForm {

    @NotBlank(message = "Podaj hasło do konta.")
    private String currentPassword;

    @NotBlank(message = "Podaj PIN.")
    @Pattern(regexp = "^[0-9]{4,6}$", message = "PIN musi zawierać od 4 do 6 cyfr.")
    private String pin;

    @NotBlank(message = "Powtórz PIN.")
    private String pinConfirmation;

    public boolean pinsMatch() {
        return pin != null && pin.equals(pinConfirmation);
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getPin() {
        return pin;
    }

    public void setPin(String pin) {
        this.pin = pin;
    }

    public String getPinConfirmation() {
        return pinConfirmation;
    }

    public void setPinConfirmation(String pinConfirmation) {
        this.pinConfirmation = pinConfirmation;
    }
}
