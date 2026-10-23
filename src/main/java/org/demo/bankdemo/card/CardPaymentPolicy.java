package org.demo.bankdemo.card;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.card.CardService.*;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Pure shared validation, normalization and DTO mapping; no transactions or mutable state. */
final class CardPaymentPolicy {
    private CardPaymentPolicy() { }
    static final ZoneId BANK_ZONE = ZoneId.of("Europe/Warsaw");
    public static final BigDecimal DEFAULT_DAILY_LIMIT = new BigDecimal("5000.00");
    static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
                    .withZone(BANK_ZONE);


    static TransactionResult result(CardTransaction transaction) {
        return new TransactionResult(
                transaction.getCurrency(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getReason(),
                transaction.getChannel()
        );
    }

    static void validate(
            String merchantName,
            CurrencyCode currency,
            CardPaymentChannel channel,
            BigDecimal amount,
            String idempotencyKey
    ) {
        if (merchantName == null || merchantName.isBlank() || merchantName.length() > 120) {
            throw new CardException("Podaj poprawna nazwe sprzedawcy.");
        }
        if (currency == null) {
            throw new CardException("Wybierz walute transakcji.");
        }
        if (channel == null) {
            throw new CardException("Wybierz kanal platnosci.");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 19) {
            throw new CardException("Podaj dodatnia kwote z maksymalnie dwoma miejscami po przecinku.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new CardException("Brak poprawnego klucza operacji.");
        }
    }

    static BigDecimal limit(BigDecimal value) {
        return value == null ? DEFAULT_DAILY_LIMIT : value.setScale(2);
    }

    static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    static String maskedNumber() {
        return "**** **** **** " + "%04d".formatted(ThreadLocalRandom.current().nextInt(10_000));
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static Account accountById(List<Account> accounts, Long id) {
        return accounts.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new CardException("Nie znaleziono zablokowanego rachunku."));
    }

    static void validateOnline(
            String onlineNumber,
            String cvv,
            String expiry,
            String merchantAccountNumber,
            String orderId,
            CurrencyCode currency,
            BigDecimal amountPln,
            BigDecimal amount,
            String title,
            String idempotencyKey
    ) {
        if (onlineNumber == null || !onlineNumber.strip().matches("\\d{16}")) {
            throw new CardException("Podaj 16-cyfrowy numer karty demonstracyjnej.");
        }
        if (cvv == null || !cvv.strip().matches("\\d{3}")) {
            throw new CardException("Podaj poprawny kod CVV.");
        }
        parseExpiry(expiry);
        if (merchantAccountNumber == null || !merchantAccountNumber.matches("\\d{26}")) {
            throw new CardException("Nieprawidlowy rachunek odbiorcy.");
        }
        if (orderId == null || orderId.isBlank() || orderId.length() > 100) {
            throw new CardException("Nieprawidlowy numer zamowienia.");
        }
        if (currency == null || amountPln == null || amountPln.signum() <= 0
                || amountPln.scale() > 2 || amountPln.precision() > 19
                || amount == null || amount.signum() <= 0
                || amount.scale() > 2 || amount.precision() > 19) {
            throw new CardException("Podaj dodatnia kwote z maksymalnie dwoma miejscami po przecinku.");
        }
        if (title == null || title.isBlank() || title.length() > 140) {
            throw new CardException("Podaj poprawny tytul platnosci.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new CardException("Brak poprawnego klucza operacji.");
        }
    }

    static java.time.YearMonth parseExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) {
            throw new CardException("Data waznosci karty musi miec format MM/RR.");
        }
        try {
            return java.time.YearMonth.parse(expiry.strip(),
                    java.time.format.DateTimeFormatter.ofPattern("MM/yy"));
        } catch (java.time.DateTimeException exception) {
            throw new CardException("Data waznosci karty musi miec format MM/RR.");
        }
    }

    static OnlinePaymentResult onlineResult(CardTransaction transaction, String reference, boolean replayed) {
        return new OnlinePaymentResult(
                transaction.getStatus(), transaction.getAmount(), transaction.getCurrency(),
                transaction.getAmountInPln(), transaction.getReason(), reference,
                transaction.getCard().getAccount().getAccountNumber(), replayed
        );
    }
}
