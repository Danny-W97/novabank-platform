package org.demo.bankdemo.recipient;

/** Missing and foreign IDs have the same public result; no ownership information is disclosed. */
@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
public final class RecipientNotFoundException extends RecipientException {
    public RecipientNotFoundException(){super(null,"Nie znaleziono odbiorcy.");}
}
