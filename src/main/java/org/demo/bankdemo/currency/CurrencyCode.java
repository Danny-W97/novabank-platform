package org.demo.bankdemo.currency;

public enum CurrencyCode {
    PLN("zł"),
    EUR("€"),
    USD("$"),
    GBP("£"),
    CHF("CHF");

    private final String symbol;

    CurrencyCode(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }
}
