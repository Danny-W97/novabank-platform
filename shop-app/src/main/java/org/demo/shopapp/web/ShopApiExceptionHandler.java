package org.demo.shopapp.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ShopApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ResponseEntityBody> validation(MethodArgumentNotValidException exception) {
        var message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": nieprawid\u0142owa warto\u015b\u0107")
                .orElse("Sprawd\u017a dane formularza.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ResponseEntityBody(HttpStatus.BAD_REQUEST.value(), message));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ResponseEntityBody> dataIntegrity(HttpServletRequest request) {
        var path = request.getRequestURI();
        var detail = path.startsWith("/api/orders/") && path.endsWith("/pay")
                ? "Klucz idempotencji p\u0142atno\u015bci zosta\u0142 ju\u017c u\u017cyty. U\u017cyj nowego klucza dla nowej p\u0142atno\u015bci."
                : "/api/auth/register".equals(path)
                ? "Nie mo\u017cna zapisa\u0107 konta. Sprawd\u017a, czy e-mail nie jest ju\u017c zarejestrowany i czy migracje bazy zosta\u0142y wykonane."
                : "Nie mo\u017cna zapisa\u0107 danych. Sprawd\u017a konflikt danych lub stan migracji bazy.";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ResponseEntityBody(
                HttpStatus.CONFLICT.value(),
                detail
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ResponseEntityBody> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new ResponseEntityBody(
                exception.getStatusCode().value(),
                exception.getReason() == null ? "Operacja nie powiod\u0142a si\u0119." : exception.getReason()
        ));
    }

    public record ResponseEntityBody(int status, String detail) {
    }
}
