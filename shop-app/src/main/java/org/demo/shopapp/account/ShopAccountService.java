package org.demo.shopapp.account;

import org.demo.shopapp.auth.ShopUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ShopAccountService {

    private final ShopAccountRepository repository;

    public ShopAccountService(ShopAccountRepository repository) {
        this.repository = repository;
    }

    public ProfileResponse profile(ShopUserDetails user) {
        var row = repository.findProfile(user.getCustomerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found."));
        return ProfileResponse.from(row);
    }

    public ProfileResponse updateProfile(ShopUserDetails user, ProfileRequest request) {
        repository.updateProfile(
                user.getCustomerId(),
                clean(request.firstName()),
                clean(request.lastName()),
                clean(request.phone())
        );
        return profile(user);
    }

    public AddressResponse address(ShopUserDetails user) {
        return repository.findAddress(user.getId())
                .map(AddressResponse::from)
                .orElseGet(AddressResponse::empty);
    }

    public AddressResponse updateAddress(ShopUserDetails user, AddressRequest request) {
        var values = new String[]{
                request.recipientName(), request.street(), request.houseNumber(),
                request.postalCode(), request.city(), request.country()
        };
        for (var value : values) {
            if (value == null || value.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uzupełnij wszystkie wymagane pola adresu.");
            }
        }
        repository.upsertAddress(
                user.getId(),
                clean(request.recipientName()),
                clean(request.street()),
                clean(request.houseNumber()),
                clean(request.apartmentNumber()),
                clean(request.postalCode()),
                clean(request.city()),
                clean(request.country())
        );
        return address(user);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
