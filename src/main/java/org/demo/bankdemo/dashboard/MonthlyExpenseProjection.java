package org.demo.bankdemo.dashboard;

import java.math.BigDecimal;

public interface MonthlyExpenseProjection {

    String getMonthKey();

    BigDecimal getTotal();
}
