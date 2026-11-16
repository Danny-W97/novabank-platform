package org.demo.shopapp.account;

public record ProfileResponse(
        String email,
        String customerId,
        String firstName,
        String lastName,
        String phone
) {

    static ProfileResponse from(ShopAccountRepository.ProfileRow row) {
        return new ProfileResponse(row.email(), row.customerId(), row.firstName(), row.lastName(), row.phone());
    }
}
