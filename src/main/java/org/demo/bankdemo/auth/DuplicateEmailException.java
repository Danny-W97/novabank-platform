package org.demo.bankdemo.auth;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("Nie można utworzyć konta dla podanego adresu e-mail.");
    }
}
