package org.demo.bankdemo.recipient;

import org.demo.bankdemo.account.AccountNumberGenerator;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class RecipientService {

    private final SavedRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    public RecipientService(
            SavedRecipientRepository recipientRepository,
            UserRepository userRepository,
            AccountRepository accountRepository
    ) {
        this.recipientRepository = recipientRepository;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public List<RecipientView> recipientsFor(String email) {
        return recipientRepository.findAllByOwnerEmailOrderByAliasAsc(normalizeEmail(email))
                .stream()
                .map(RecipientService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecipientView recipientFor(String email, Long id) {
        return toView(ownedRecipient(email, id));
    }

    @Transactional(readOnly = true)
    public boolean isSavedRecipient(String email, String accountNumber) {
        return recipientRepository.existsByOwnerEmailAndAccountNumber(
                normalizeEmail(email),
                accountNumber.replaceAll("\\s+", "").replaceFirst("^(?i)PL", "")
        );
    }

    @Transactional(readOnly = true)
    public RecipientForm formFor(String email, Long id) {
        var recipient = ownedRecipient(email, id);
        var form = new RecipientForm();
        form.setAlias(recipient.getAlias());
        form.setRecipientName(recipient.getRecipientName());
        form.setAccountNumber(recipient.getAccountNumber());
        return form;
    }

    @Transactional
    public void create(String email, RecipientForm form) {
        var normalizedEmail = normalizeEmail(email);
        var owner = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono użytkownika."));
        var accountNumber = validateAccount(normalizedEmail, form.getAccountNumber());
        if (recipientRepository.existsByOwnerEmailAndAccountNumber(normalizedEmail, accountNumber)) {
            throw new RecipientException("accountNumber", "Ten rachunek jest już zapisany.");
        }
        try {
            recipientRepository.save(new SavedRecipient(
                    owner,
                    normalize(form.getAlias()),
                    normalize(form.getRecipientName()),
                    accountNumber
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new RecipientException("accountNumber", "Ten rachunek jest już zapisany.");
        }
    }

    @Transactional
    public void update(String email, Long id, RecipientForm form) {
        var normalizedEmail = normalizeEmail(email);
        var recipient = ownedRecipient(normalizedEmail, id);
        var accountNumber = validateAccount(normalizedEmail, form.getAccountNumber());
        recipient.update(
                normalize(form.getAlias()),
                normalize(form.getRecipientName()),
                accountNumber
        );
    }

    @Transactional
    public void delete(String email, Long id) {
        recipientRepository.delete(ownedRecipient(email, id));
    }

    private SavedRecipient ownedRecipient(String email, Long id) {
        return recipientRepository.findByIdAndOwnerEmail(id, normalizeEmail(email))
                .orElseThrow(RecipientNotFoundException::new);
    }

    private String validateAccount(String ownerEmail, String rawAccountNumber) {
        var accountNumber = rawAccountNumber.replaceAll("\\s+", "")
                .replaceFirst("^(?i)PL", "");
        if (!AccountNumberGenerator.isValid(accountNumber)) {
            throw new RecipientException("accountNumber", "Podaj poprawny numer rachunku NRB.");
        }
        var account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RecipientException(
                        "accountNumber",
                        "W aplikacji demonstracyjnej można zapisać tylko istniejący rachunek."
                ));
        if (account.getOwner().getEmail().equals(ownerEmail)) {
            throw new RecipientException("accountNumber", "Nie można zapisać własnego rachunku.");
        }
        return accountNumber;
    }

    private static RecipientView toView(SavedRecipient recipient) {
        return new RecipientView(
                recipient.getId(),
                recipient.getAlias(),
                recipient.getRecipientName(),
                formatAccountNumber(recipient.getAccountNumber())
        );
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizeEmail(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String formatAccountNumber(String accountNumber) {
        return accountNumber.substring(0, 2) + " "
                + accountNumber.substring(2).replaceAll("(.{4})(?!$)", "$1 ");
    }

    public record RecipientView(
            Long id,
            String alias,
            String recipientName,
            String accountNumber
    ) {
    }
}
