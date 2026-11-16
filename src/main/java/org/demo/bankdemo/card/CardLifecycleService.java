package org.demo.bankdemo.card;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.demo.bankdemo.card.CardService.*;
import static org.demo.bankdemo.card.CardPaymentPolicy.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import java.util.List;

@Service
public class CardLifecycleService {
    private final AccountRepository accountRepository;
    private final DemoCardRepository cardRepository;
    private final Clock clock;

    public CardLifecycleService(
            AccountRepository accountRepository,
            DemoCardRepository cardRepository,
            Clock clock
    ) {
        this.accountRepository = java.util.Objects.requireNonNull(accountRepository);
        this.cardRepository = java.util.Objects.requireNonNull(cardRepository);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Transactional
    public void updateDailyLimit(String email, BigDecimal dailyLimit) {
        var account = lockedAccount(email);
        var card = cardRepository.findByAccountId(account.getId())
                .orElseThrow(() -> new CardException("Najpierw podlacz karte do rachunku."));
        card.updateDailyLimit(dailyLimit);
    }

    @Transactional
    public CardView attach(String email) {
        var account = lockedAccount(email);
        var existing = cardRepository.findByAccountId(account.getId());
        if (existing.isPresent()) {
            var card = existing.get();
            return new CardView(card.getMaskedNumber(), card.getOnlineNumber(), card.getDemoCvv(), card.getDemoExpiresAt(), card.getStatus(), card.getBlockedUntil(),
                    limit(card.getDailyLimit()), BigDecimal.ZERO.setScale(2), limit(card.getDailyLimit()), List.of());
        }
        var card = cardRepository.save(new DemoCard(account, maskedNumber()));
        return new CardView(card.getMaskedNumber(), card.getOnlineNumber(), card.getDemoCvv(), card.getDemoExpiresAt(), card.getStatus(), card.getBlockedUntil(),
                limit(card.getDailyLimit()), BigDecimal.ZERO.setScale(2), limit(card.getDailyLimit()), List.of());
    }

    @Transactional
    public void block(String email) {
        var account = lockedAccount(email);
        cardRepository.findByAccountId(account.getId()).ifPresent(DemoCard::block);
    }

    @Transactional
    public void blockTemporarily(String email, int minutes) {
        if (minutes < 1 || minutes > 10080) {
            throw new CardException("Czas blokady musi miec od 1 minuty do 7 dni.");
        }
        var account = lockedAccount(email);
        cardRepository.findByAccountId(account.getId()).ifPresent(card ->
                card.temporarilyBlock(Instant.now(clock).plusSeconds(minutes * 60L))
        );
    }

    @Transactional
    public void activate(String email) {
        var account = lockedAccount(email);
        cardRepository.findByAccountId(account.getId()).ifPresent(DemoCard::activate);
    }

    private Account lockedAccount(String email) {
        return accountRepository.findByOwnerEmailForUpdate(normalize(email))
                .orElseThrow(() -> new CardException("Zalogowany uzytkownik nie posiada rachunku."));
    }
}
