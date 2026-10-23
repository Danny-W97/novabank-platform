package org.demo.bankdemo.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** Created from trusted API-key configuration, never from a browser-provided merchant ID. */
public record MerchantIdentity(String id, Set<String> accounts) {
    public static final String REQUEST_ATTRIBUTE = "bank.payment.merchant";

    public MerchantIdentity {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid merchant identity.");
        accounts = Set.copyOf(accounts);
    }

    public void requireAccount(String account) {
        if (!accounts.contains(account))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant account is not assigned to this API client.");
    }

    public void requireOwner(String owner) {
        if (owner == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Legacy payment requires explicit merchant assignment.");
        if (!id.equals(owner))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found.");
    }
}
