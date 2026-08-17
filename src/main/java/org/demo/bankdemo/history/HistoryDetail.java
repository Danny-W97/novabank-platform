package org.demo.bankdemo.history;

public record HistoryDetail(
        Long id,
        String referenceNumber,
        String type,
        String status,
        String direction,
        String counterparty,
        String counterpartyAccount,
        String ownAccount,
        String amount,
        String title,
        String createdAt,
        String completedAt
) {
}
