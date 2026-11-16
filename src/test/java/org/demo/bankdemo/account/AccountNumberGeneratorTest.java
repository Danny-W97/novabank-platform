package org.demo.bankdemo.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountNumberGeneratorTest {

    @Test
    void generatesValidUniquePolishAccountNumber() {
        var accountRepository = mock(AccountRepository.class);
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        var generator = new AccountNumberGenerator(accountRepository);

        var accountNumber = generator.generateUnique();

        assertThat(accountNumber)
                .hasSize(26)
                .matches("\\d{26}");
        assertThat(AccountNumberGenerator.isValid(accountNumber)).isTrue();
    }

    @Test
    void rejectsInvalidAccountNumbers() {
        assertThat(AccountNumberGenerator.isValid(null)).isFalse();
        assertThat(AccountNumberGenerator.isValid("123")).isFalse();
        assertThat(AccountNumberGenerator.isValid("00".repeat(13))).isFalse();
    }
}
