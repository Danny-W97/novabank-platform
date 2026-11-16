package org.demo.bankdemo.profile;

public class TwoFactorException extends RuntimeException {

    private final String field;

    public TwoFactorException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
