package org.demo.bankdemo.account;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {

    private static final int BBAN_LENGTH = 24;
    private static final int MAX_ATTEMPTS = 20;
    private static final String POLAND_COUNTRY_CODE = "2521";

    private final AccountRepository accountRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public AccountNumberGenerator(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public String generateUnique() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            var candidate = generate();
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Nie udało się wygenerować unikalnego numeru rachunku.");
    }

    String generate() {
        var bban = new StringBuilder(BBAN_LENGTH);
        for (int i = 0; i < BBAN_LENGTH; i++) {
            bban.append(secureRandom.nextInt(10));
        }

        var checksumInput = new BigInteger(bban + POLAND_COUNTRY_CODE + "00");
        var checksum = 98 - checksumInput.mod(BigInteger.valueOf(97)).intValue();
        return "%02d%s".formatted(checksum, bban);
    }

    public static boolean isValid(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("\\d{26}")) {
            return false;
        }

        var rearranged = accountNumber.substring(2)
                + POLAND_COUNTRY_CODE
                + accountNumber.substring(0, 2);
        return new BigInteger(rearranged).mod(BigInteger.valueOf(97)).intValue() == 1;
    }
}
