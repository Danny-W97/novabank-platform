package org.demo.bankdemo.transfer;

public record TransferPreview(
        String recipientName,
        String destinationAccountNumber,
        String amount,
        String title
) {
}
