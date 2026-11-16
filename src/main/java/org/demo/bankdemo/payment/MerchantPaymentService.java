package org.demo.bankdemo.payment;

import org.demo.bankdemo.card.CardService;
import org.demo.bankdemo.card.CardPaymentRequest;
import org.demo.bankdemo.card.CardTransactionStatus;
import org.demo.bankdemo.card.CardPaymentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantPaymentService {
    private final PaymentPurchaseGuard purchases;
    private final CardService cards;
    private final PaymentService payments;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public MerchantPaymentService(PaymentPurchaseGuard purchases, CardService cards, PaymentService payments,
                                  org.springframework.transaction.PlatformTransactionManager manager) {
        this.purchases = purchases;
        this.cards = cards;
        this.payments = payments;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public CardPaymentResponse payCard(MerchantIdentity merchant, String key, CardPaymentRequest request) {
        if (key == null || key.isBlank() || key.strip().length() > 64)
            throw new org.demo.bankdemo.card.CardException("Idempotency-Key jest wymagany.");
        var normalizedKey = key.strip();
        try {
            return transactions.execute(status -> payCardInTransaction(merchant,normalizedKey,request));
        } catch(org.demo.bankdemo.card.CardException exception) {
            // A business rejection is recorded only after the entire charging transaction rolled back.
            return transactions.execute(status -> {
                purchases.begin(merchant,request.orderId(),request.amountPln(),"PLN",request.merchantAccountNumber(),
                        normalizedKey,"CARD",request.amount(),request.currency(),request.title());
                var snapshot=purchases.snapshot(normalizedKey);
                if("SUCCEEDED".equals(snapshot.state()))
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                            "Payment already succeeded; verify the original request and read its status.");
                boolean replayed="DECLINED".equals(snapshot.state());
                if(!replayed) purchases.finish(merchant.id(),request.orderId(),normalizedKey,null);
                return declined(merchant,request.orderId(),request.amountPln(),request.amount(),request.currency(),replayed);
            });
        }
    }

    private CardPaymentResponse payCardInTransaction(MerchantIdentity merchant,String key,CardPaymentRequest request) {
        purchases.begin(merchant, request.orderId(), request.amountPln(), "PLN",
                request.merchantAccountNumber(), key, "CARD",request.amount(),request.currency(),request.title());
        if("DECLINED".equals(purchases.snapshot(key).state()))
            return declined(merchant,request.orderId(),request.amountPln(),request.amount(),request.currency(),true);
        var result = cards.payOnline(request.cardNumber(), request.cvv(), request.expiry(),
                request.merchantAccountNumber(), request.orderId(), request.currencyCode(),
                request.amountPln(), request.amount(), request.title(), key, merchant.id());
        if (!result.replayed()) purchases.finish(merchant.id(), request.orderId(), key,
                result.status() == CardTransactionStatus.COMPLETED ? result.referenceNumber() : null);
        return CardPaymentResponse.from(request.orderId(), result).withMerchant(merchant.id());
    }

    @Transactional(readOnly = true)
    public CardPaymentResponse cardStatus(MerchantIdentity merchant, String key, String account, String order) {
        purchases.requireKeyOwner(merchant, key, order, account);
        var snapshot=purchases.snapshot(key);
        if("DECLINED".equals(snapshot.state()) && snapshot.chargedAmount()!=null) {
            return declined(merchant,order,purchases.amount(merchant.id(),order),snapshot.chargedAmount(),snapshot.chargedCurrency(),true);
        }
        return CardPaymentResponse.from(order, cards.onlinePaymentStatus(key, account, order)).withMerchant(merchant.id());
    }

    @Transactional
    public PaymentResponse refund(MerchantIdentity merchant, String key, RefundRequest request, String title) {
        purchases.requireReferenceOwner(merchant, request.originalReferenceNumber());
        return payments.refundByReference(key, request.originalReferenceNumber(), request.merchantAccountNumber(),
                request.customerAccountNumber(), request.orderId(), request.amount(), request.currency(), title);
    }

    private static CardPaymentResponse declined(MerchantIdentity merchant,String order,java.math.BigDecimal pln,
            java.math.BigDecimal charged,String currency,boolean replayed) {
        return new CardPaymentResponse(null,order,charged,currency,pln,"DECLINED","Płatność kartą została odrzucona.",null,replayed,merchant.id());
    }
}
