package org.demo.bankdemo.web;

import org.demo.bankdemo.payment.PaymentException;
import org.demo.bankdemo.card.CardException;
import org.demo.bankdemo.currency.ExchangeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestControllerAdvice
public class PaymentApiExceptionHandler {

    @ExceptionHandler({org.springframework.dao.TransientDataAccessException.class,
            org.springframework.transaction.TransactionException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.orm.jpa.JpaSystemException.class})
    org.springframework.http.ResponseEntity<ErrorResponse> temporarilyUnavailable(RuntimeException exception) {
        // Invoked after the service transaction exits. A lost commit acknowledgement is not a decline.
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, "1")
                .body(new ErrorResponse("PAYMENT_OUTCOME_UNCONFIRMED",
                        "Wynik operacji wymaga sprawdzenia. Odczytaj status istniejącej próby; "
                                + "ponawiaj wyłącznie te same dane z tym samym Idempotency-Key. "
                                + "Nie rozpoczynaj nowej płatności."));
    }

    @ExceptionHandler(PaymentException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ErrorResponse paymentError(PaymentException exception) {
        return new ErrorResponse("PAYMENT_REJECTED", exception.getMessage());
    }

    @ExceptionHandler({CardException.class, ExchangeException.class})
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ErrorResponse cardOrExchangeError(RuntimeException exception) {
        return new ErrorResponse("PAYMENT_REJECTED", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse validationError(MethodArgumentNotValidException exception) {
        var message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": invalid value")
                .orElse("Invalid payment request");
        return new ErrorResponse("VALIDATION_ERROR", message);
    }

    public record ErrorResponse(String code, String message) {
    }
}
