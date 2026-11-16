package org.demo.shopapp.payment;

import java.math.BigDecimal;

public record BankCardQuoteRequest(BigDecimal amountPln, String currency) {
}
