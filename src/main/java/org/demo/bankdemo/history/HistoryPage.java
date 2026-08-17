package org.demo.bankdemo.history;

import java.util.List;

public record HistoryPage(
        List<TransactionItem> transactions,
        long totalElements,
        int currentPage,
        int totalPages,
        long firstElement,
        long lastElement,
        boolean hasPrevious,
        boolean hasNext,
        List<Integer> pageNumbers,
        boolean filtered
) {

    public record TransactionItem(
            Long id,
            String icon,
            String color,
            String counterparty,
            String title,
            String date,
            String status,
            String amount,
            String direction
    ) {
    }
}
