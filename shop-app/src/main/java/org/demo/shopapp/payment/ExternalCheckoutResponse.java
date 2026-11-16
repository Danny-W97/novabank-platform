package org.demo.shopapp.payment;

public record ExternalCheckoutResponse(String provider, String paymentId, String checkoutUrl) {
}
