package org.demo.bankdemo.transfer;

public record TransferResult(
        String referenceNumber,
        String recipientName,
        String destinationAccountNumber,
        String amount,
        String title,
        String completedAt
) {
}
