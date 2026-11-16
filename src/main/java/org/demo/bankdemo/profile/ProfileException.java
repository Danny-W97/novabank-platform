package org.demo.bankdemo.profile;

public class ProfileException extends RuntimeException {

    private final String field;

    public ProfileException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
