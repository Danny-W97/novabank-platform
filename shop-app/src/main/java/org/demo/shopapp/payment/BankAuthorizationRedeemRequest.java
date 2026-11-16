package org.demo.shopapp.payment;

public record BankAuthorizationRedeemRequest(String code, String state) {
}
