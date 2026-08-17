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
