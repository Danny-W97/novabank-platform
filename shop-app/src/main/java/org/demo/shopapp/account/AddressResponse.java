package org.demo.shopapp.account;

public record AddressResponse(
        String recipientName,
        String street,
        String houseNumber,
        String apartmentNumber,
        String postalCode,
        String city,
        String country
) {

    static AddressResponse from(ShopAccountRepository.AddressRow row) {
        return new AddressResponse(
                row.recipientName(), row.street(), row.houseNumber(), row.apartmentNumber(),
                row.postalCode(), row.city(), row.country()
        );
    }

    static AddressResponse empty() {
        return new AddressResponse("", "", "", "", "", "", "Polska");
    }
}
