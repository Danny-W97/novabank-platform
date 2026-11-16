package org.demo.shopapp.account;

import jakarta.validation.Valid;
import org.demo.shopapp.auth.ShopUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class ShopAccountController {

    private final ShopAccountService accountService;

    public ShopAccountController(ShopAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/profile")
    ProfileResponse profile(@AuthenticationPrincipal ShopUserDetails user) {
        return accountService.profile(user);
    }

    @PutMapping("/profile")
    ProfileResponse updateProfile(
            @AuthenticationPrincipal ShopUserDetails user,
            @Valid @RequestBody ProfileRequest request
    ) {
        return accountService.updateProfile(user, request);
    }

    @GetMapping("/address")
    AddressResponse address(@AuthenticationPrincipal ShopUserDetails user) {
        return accountService.address(user);
    }

    @PutMapping("/address")
    AddressResponse updateAddress(
            @AuthenticationPrincipal ShopUserDetails user,
            @Valid @RequestBody AddressRequest request
    ) {
        return accountService.updateAddress(user, request);
    }
}
