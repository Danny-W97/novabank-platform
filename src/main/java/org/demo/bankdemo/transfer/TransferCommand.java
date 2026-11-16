package org.demo.bankdemo.transfer;

import java.io.Serializable;
import java.math.BigDecimal;

public record TransferCommand(
        String idempotencyKey,
        String recipientName,
        String destinationAccountNumber,
        BigDecimal amount,
        String title
) implements Serializable {
}
