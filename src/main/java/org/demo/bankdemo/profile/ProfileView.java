package org.demo.bankdemo.profile;

public record ProfileView(
        String firstName,
        String lastName,
        String email,
        String phone,
        String birthDate,
        String passwordChangedAt
) {
}
