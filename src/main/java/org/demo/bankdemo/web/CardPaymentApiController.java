package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.card.CardPaymentRequest;
import org.demo.bankdemo.card.CardPaymentResponse;
import org.demo.bankdemo.card.CardService;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.currency.ExchangeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/card-payments")
public class CardPaymentApiController {

    private final CardService cardService;
    private final ExchangeService exchangeService;
    private final org.demo.bankdemo.payment.MerchantPaymentService merchantPayments;

    public CardPaymentApiController(CardService cardService, ExchangeService exchangeService,
                                    org.demo.bankdemo.payment.MerchantPaymentService merchantPayments) {
        this.cardService = cardService;
        this.exchangeService = exchangeService;
        this.merchantPayments = merchantPayments;
    }

    @PostMapping("/quote")
    CardPaymentQuoteResponse quote(@Valid @RequestBody CardPaymentQuoteRequest request) {
        var currency = request.currencyCode();
        if (currency == CurrencyCode.PLN) {
            return new CardPaymentQuoteResponse(
                    request.amountPln().setScale(2), CurrencyCode.PLN.name(),
                    request.amountPln().setScale(2), BigDecimal.ONE.setScale(6),
                    Instant.now().plusSeconds(120)
            );
        }
        var quote = exchangeService.quote(CurrencyCode.PLN, currency, request.amountPln());
        return new CardPaymentQuoteResponse(
                quote.sourceAmount(), quote.targetCurrency().name(), quote.targetAmount(),
                quote.appliedRate(), quote.createdAt().plusSeconds(120)
        );
    }

    @PostMapping
    CardPaymentResponse pay(
            @org.springframework.web.bind.annotation.RequestAttribute(org.demo.bankdemo.payment.MerchantIdentity.REQUEST_ATTRIBUTE)
            org.demo.bankdemo.payment.MerchantIdentity merchant,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CardPaymentRequest request
    ) {
        return merchantPayments.payCard(merchant, idempotencyKey, request);
    }

    @GetMapping("/status/{idempotencyKey}")
    CardPaymentResponse status(@org.springframework.web.bind.annotation.RequestAttribute(org.demo.bankdemo.payment.MerchantIdentity.REQUEST_ATTRIBUTE)
                               org.demo.bankdemo.payment.MerchantIdentity merchant,
                               @PathVariable String idempotencyKey,
                               @RequestParam String merchantAccountNumber,
                               @RequestParam String orderId) {
        return merchantPayments.cardStatus(merchant, idempotencyKey, merchantAccountNumber, orderId);
    }

    public record CardPaymentQuoteRequest(
            @jakarta.validation.constraints.NotNull
            @jakarta.validation.constraints.DecimalMin("0.01")
            @jakarta.validation.constraints.Digits(integer = 17, fraction = 2)
            BigDecimal amountPln,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{3}")
            String currency
    ) {
        CurrencyCode currencyCode() {
            try {
                return CurrencyCode.valueOf(currency);
            } catch (IllegalArgumentException exception) {
                throw new org.demo.bankdemo.card.CardException("Nieobslugiwany kod waluty.");
            }
        }
    }

    public record CardPaymentQuoteResponse(
            BigDecimal amountPln,
            String currency,
            BigDecimal amount,
            BigDecimal rate,
            Instant expiresAt
    ) {
    }
}
