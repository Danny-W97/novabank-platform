package org.demo.bankdemo.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegistrationForm {

    @NotBlank(message = "Podaj imię.")
    @Size(max = 100, message = "Imię może mieć maksymalnie 100 znaków.")
    private String firstName;

    @NotBlank(message = "Podaj nazwisko.")
    @Size(max = 100, message = "Nazwisko może mieć maksymalnie 100 znaków.")
    private String lastName;

    @NotBlank(message = "Podaj adres e-mail.")
    @Email(message = "Podaj poprawny adres e-mail.")
    @Size(max = 254, message = "Adres e-mail jest zbyt długi.")
    private String email;

    @Pattern(
            regexp = "^$|^[0-9+() -]{7,32}$",
            message = "Podaj poprawny numer telefonu albo pozostaw pole puste."
    )
    private String phone;

    @NotBlank(message = "Podaj hasło.")
    @Size(min = 8, max = 72, message = "Hasło musi mieć od 8 do 72 znaków.")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "Hasło musi zawierać małą i wielką literę oraz cyfrę."
    )
    private String password;

    @NotBlank(message = "Powtórz hasło.")
    private String passwordConfirmation;

    @AssertTrue(message = "Potwierdź edukacyjny charakter aplikacji.")
    private boolean termsAccepted;

    public boolean passwordsMatch() {
        return password != null && password.equals(passwordConfirmation);
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordConfirmation() {
        return passwordConfirmation;
    }

    public void setPasswordConfirmation(String passwordConfirmation) {
        this.passwordConfirmation = passwordConfirmation;
    }

    public boolean isTermsAccepted() {
        return termsAccepted;
    }

    public void setTermsAccepted(boolean termsAccepted) {
        this.termsAccepted = termsAccepted;
    }
}
