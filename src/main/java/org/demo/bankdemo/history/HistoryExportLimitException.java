package org.demo.bankdemo.history;

@org.springframework.web.bind.annotation.ResponseStatus(
        code = org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
        reason = "Eksport przekracza limit. Zawęź zakres dat lub pozostałe filtry i spróbuj ponownie.")
public class HistoryExportLimitException extends RuntimeException {
}
