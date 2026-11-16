package org.demo.bankdemo.dashboard;

import java.math.BigDecimal;

public interface DashboardTotalsProjection {

    BigDecimal getTotal();

    long getTransactionCount();
}
