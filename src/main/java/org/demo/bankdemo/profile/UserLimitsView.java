package org.demo.bankdemo.profile;

import java.math.BigDecimal;

public record UserLimitsView(
        BigDecimal transferSingleLimit,
        BigDecimal transferDailyLimit,
        BigDecimal paymentSingleLimit,
        BigDecimal paymentDailyLimit,
        BigDecimal cardDailyLimit,
        boolean cardAttached,
        BigDecimal totpRequiredAbove,
        BigDecimal bankTotpRequiredAbove,
        BigDecimal bankSingleLimit,
        BigDecimal bankDailyLimit
) {
}
