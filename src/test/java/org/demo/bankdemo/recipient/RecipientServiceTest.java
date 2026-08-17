package org.demo.bankdemo.recipient;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {

    private static final String OWNER_EMAIL = "owner@example.pl";
    private static final String ACCOUNT_NUMBER = "61109010140000071219812874";

    @Mock
    private SavedRecipientRepository recipientRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    private RecipientService service;
    private User owner;

    @BeforeEach
    void setUp() {
        owner = new User(OWNER_EMAIL, "hash", "Jan", "Kowalski", null);
        service = new RecipientService(recipientRepository, userRepository, accountRepository);
    }

    @Test
    void savesNormalizedRecipientOwnedByAuthenticatedUser() {
        var recipientOwner = new User("anna@example.pl", "hash", "Anna", "Nowak", null);
        var destination = new Account(recipientOwner, ACCOUNT_NUMBER, BigDecimal.ZERO);
        when(userRepository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        when(accountRepository.findByAccountNumber(ACCOUNT_NUMBER))
                .thenReturn(Optional.of(destination));
        var form = form();

        service.create(OWNER_EMAIL, form);

        var captor = ArgumentCaptor.forClass(SavedRecipient.class);
        verify(recipientRepository).save(captor.capture());
        assertThat(captor.getValue().getOwner()).isSameAs(owner);
        assertThat(captor.getValue().getAlias()).isEqualTo("Anna prywatnie");
        assertThat(captor.getValue().getAccountNumber()).isEqualTo(ACCOUNT_NUMBER);
    }

    @Test
    void neverReturnsRecipientOwnedByAnotherUser() {
        when(recipientRepository.findByIdAndOwnerEmail(7L, OWNER_EMAIL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recipientFor(OWNER_EMAIL, 7L))
                .isInstanceOf(RecipientException.class);
    }

    private static RecipientForm form() {
        var form = new RecipientForm();
        form.setAlias("  Anna   prywatnie ");
        form.setRecipientName(" Anna Nowak ");
        form.setAccountNumber(ACCOUNT_NUMBER);
        return form;
    }
}
