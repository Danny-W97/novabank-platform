package org.demo.bankdemo.recipient;

public class RecipientException extends RuntimeException {

    private final String field;

    public RecipientException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
