package org.demo.bankdemo.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    boolean existsByAccountNumber(String accountNumber);

    Optional<Account> findByOwnerEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account from Account account
            join fetch account.owner
            where account.owner.email = :email
            """)
    Optional<Account> findByOwnerEmailForUpdate(@Param("email") String email);

    Optional<Account> findByAccountNumber(String accountNumber);

    @Query("""
            select account
            from Account account
            join fetch account.owner
            order by account.createdAt desc
            """)
    List<Account> findAllWithOwners();

    @Query("""
            select account
            from Account account
            join fetch account.owner
            where lower(account.owner.email) like lower(concat('%', :query, '%'))
               or lower(concat(account.owner.firstName, ' ', account.owner.lastName)) like lower(concat('%', :query, '%'))
               or account.accountNumber like concat('%', :query, '%')
            order by account.createdAt desc
            """)
    List<Account> searchAllWithOwners(@Param("query") String query);

    @Query("""
            select account
            from Account account
            join fetch account.owner
            where account.id = :accountId
            """)
    Optional<Account> findByIdWithOwner(@Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account
            from Account account
            join fetch account.owner
            where account.id in :accountIds
            order by account.id
            """)
    List<Account> findAllByIdForUpdate(@Param("accountIds") List<Long> accountIds);
}
