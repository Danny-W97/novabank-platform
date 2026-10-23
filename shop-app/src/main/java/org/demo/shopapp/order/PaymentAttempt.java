package org.demo.shopapp.order;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentAttempt(String id, long orderId, String merchantId, String provider,
        String key, String state, String correlation, BigDecimal chargedAmount, String chargedCurrency,
        String authorizationId, String authorizationUrl, String externalId, String checkoutUrl,
        String reference, String customerAccount, Instant createdAt, Instant checkedAt, long version) {
    public boolean succeeded() { return "SUCCEEDED".equals(state); }
    public boolean terminal() { return succeeded() || "DECLINED".equals(state) || "CANCELLED".equals(state) || "EXPIRED".equals(state); }
}
