package org.demo.bankdemo.dashboard;

import org.demo.bankdemo.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record DashboardTransactionRow(
        TransactionType type,
        Long sourceAccountId,
        Long destinationAccountId,
        String senderName,
        String recipientName,
        BigDecimal amount,
        String currency,
        String title,
        Instant createdAt
) {
}
