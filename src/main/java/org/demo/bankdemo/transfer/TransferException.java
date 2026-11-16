package org.demo.bankdemo.transfer;

public class TransferException extends RuntimeException {

    private final String field;

    public TransferException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
