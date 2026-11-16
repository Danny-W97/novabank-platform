package org.demo.shopapp.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class ShopInput {

    private ShopInput() {
    }

    public static String idempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key is required and must have at most 64 characters."
            );
        }
        return value.strip();
    }
}
