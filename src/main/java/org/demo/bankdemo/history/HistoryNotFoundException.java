package org.demo.bankdemo.history;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class HistoryNotFoundException extends RuntimeException {

    public HistoryNotFoundException() {
        super("Nie znaleziono transakcji.");
    }
}
