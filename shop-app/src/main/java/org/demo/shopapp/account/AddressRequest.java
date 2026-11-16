package org.demo.shopapp.account;

import jakarta.validation.constraints.Size;

public record AddressRequest(
        @Size(max = 150) String recipientName,
        @Size(max = 150) String street,
        @Size(max = 20) String houseNumber,
        @Size(max = 20) String apartmentNumber,
        @Size(max = 16) String postalCode,
        @Size(max = 100) String city,
        @Size(max = 100) String country
) {
}
