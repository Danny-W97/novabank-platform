package org.demo.bankdemo.payment;

public class PaymentException extends RuntimeException {

    public PaymentException(String message) {
        super(message);
    }
}
