package org.demo.bankdemo.dashboard;

import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DashboardTransactionRepository extends Repository<BankTransaction, Long> {

    @Query("""
            select new org.demo.bankdemo.dashboard.DashboardTransactionRow(
                transaction.type,
                source.id,
                destination.id,
                transaction.senderNameSnapshot,
                transaction.recipientNameSnapshot,
                transaction.amount,
                transaction.currency,
                transaction.title,
                transaction.createdAt
            )
            from BankTransaction transaction
            left join transaction.sourceAccount source
            join transaction.destinationAccount destination
            where (source.id = :accountId or destination.id = :accountId)
              and transaction.status = :status
            order by transaction.createdAt desc
            """)
    List<DashboardTransactionRow> findRecent(
            @Param("accountId") Long accountId,
            @Param("status") TransactionStatus status,
            Pageable pageable
    );

    @Query("""
            select sum(transaction.amount) as total,
                   count(transaction) as transactionCount
            from BankTransaction transaction
            where transaction.sourceAccount.id = :accountId
              and transaction.status = :status
              and transaction.createdAt >= :from
              and transaction.createdAt < :to
            """)
    DashboardTotalsProjection outgoingTotals(
            @Param("accountId") Long accountId,
            @Param("status") TransactionStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
            select sum(transaction.amount) as total,
                   count(transaction) as transactionCount
            from BankTransaction transaction
            where transaction.destinationAccount.id = :accountId
              and transaction.status = :status
              and transaction.createdAt >= :from
              and transaction.createdAt < :to
            """)
    DashboardTotalsProjection incomingTotals(
            @Param("accountId") Long accountId,
            @Param("status") TransactionStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query(value = """
            select to_char(date_trunc('month', created_at at time zone 'Europe/Warsaw'), 'YYYY-MM') as "monthKey",
                   sum(amount) as total
            from bank_transactions
            where source_account_id = :accountId
              and status = :status
              and created_at >= :from
            group by date_trunc('month', created_at at time zone 'Europe/Warsaw')
            order by date_trunc('month', created_at at time zone 'Europe/Warsaw')
            """, nativeQuery = true)
    List<MonthlyExpenseProjection> monthlyExpenses(
            @Param("accountId") Long accountId,
            @Param("status") String status,
            @Param("from") Instant from
    );
}
