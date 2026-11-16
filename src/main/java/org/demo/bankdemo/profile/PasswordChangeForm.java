package org.demo.bankdemo.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class PasswordChangeForm {

    @NotBlank(message = "Podaj obecne hasło.")
    private String currentPassword;

    @NotBlank(message = "Podaj nowe hasło.")
    @Size(min = 8, max = 72, message = "Hasło musi mieć od 8 do 72 znaków.")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "Hasło musi zawierać małą i wielką literę oraz cyfrę."
    )
    private String newPassword;

    @NotBlank(message = "Powtórz nowe hasło.")
    private String passwordConfirmation;

    public boolean passwordsMatch() {
        return newPassword != null && newPassword.equals(passwordConfirmation);
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getPasswordConfirmation() {
        return passwordConfirmation;
    }

    public void setPasswordConfirmation(String passwordConfirmation) {
        this.passwordConfirmation = passwordConfirmation;
    }
}
