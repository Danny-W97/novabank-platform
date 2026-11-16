package org.demo.bankdemo.recipient;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedRecipientRepository extends JpaRepository<SavedRecipient, Long> {

    List<SavedRecipient> findAllByOwnerEmailOrderByAliasAsc(String email);

    Optional<SavedRecipient> findByIdAndOwnerEmail(Long id, String email);

    boolean existsByOwnerEmailAndAccountNumber(String email, String accountNumber);
}
