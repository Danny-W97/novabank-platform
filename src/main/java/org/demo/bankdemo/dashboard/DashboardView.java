package org.demo.bankdemo.dashboard;

import java.util.List;

public record DashboardView(
        String firstName,
        String fullName,
        String initials,
        String email,
        String phone,
        String customerNumber,
        String accountNumber,
        String balance,
        String accountStatus,
        String currentDate,
        String monthName,
        String lastActivity,
        String expenses,
        long expenseCount,
        String income,
        long incomeCount,
        List<TransactionItem> transactions
) {

    public record TransactionItem(
            String icon,
            String color,
            String name,
            String date,
            String amount,
            String direction
    ) {
    }
}
