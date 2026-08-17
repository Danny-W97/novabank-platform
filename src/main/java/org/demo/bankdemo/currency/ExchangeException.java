package org.demo.bankdemo.currency;

public class ExchangeException extends RuntimeException {

    private final String field;

    public ExchangeException(String message) {
        this(null, message);
    }

    public ExchangeException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
