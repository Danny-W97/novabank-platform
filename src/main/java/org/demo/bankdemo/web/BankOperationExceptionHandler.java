package org.demo.bankdemo.web;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = {
        TransferController.class, ScheduledTransferController.class, ExchangeController.class
})
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class BankOperationExceptionHandler {
    @ExceptionHandler({TransientDataAccessException.class, org.springframework.transaction.TransactionException.class,
            org.springframework.dao.DataAccessResourceFailureException.class, org.springframework.orm.jpa.JpaSystemException.class})
    ResponseEntity<String> temporarilyBusy(RuntimeException exception) {
        // This runs after the service transaction exits, not inside a transaction marked for rollback.
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body("Wynik operacji nie został potwierdzony z powodu problemu bazy lub transakcji. "
                        + "Sprawdź historię operacji. Możesz ponowić ten sam formularz z tym samym kluczem operacji.");
    }
}
