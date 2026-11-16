package org.demo.bankdemo.profile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public class ProfileUpdateForm {

    @NotBlank(message = "Podaj imię.")
    @Size(max = 100, message = "Imię może mieć maksymalnie 100 znaków.")
    private String firstName;

    @NotBlank(message = "Podaj nazwisko.")
    @Size(max = 100, message = "Nazwisko może mieć maksymalnie 100 znaków.")
    private String lastName;

    @Pattern(
            regexp = "^$|^[0-9+() -]{7,32}$",
            message = "Podaj poprawny numer telefonu albo pozostaw pole puste."
    )
    private String phone;

    @Past(message = "Data urodzenia musi być datą z przeszłości.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate birthDate;

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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }
}
