package org.demo.bankdemo.card;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DemoCardRepository extends JpaRepository<DemoCard, Long> {

    Optional<DemoCard> findByAccountId(Long accountId);

    @Query("select card from DemoCard card join fetch card.account account join fetch account.owner where card.onlineNumber = :onlineNumber")
    Optional<DemoCard> findByOnlineNumber(String onlineNumber);
}
