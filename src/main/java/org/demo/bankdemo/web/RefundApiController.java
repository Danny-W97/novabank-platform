package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.payment.PaymentResponse;
import org.demo.bankdemo.payment.PaymentService;
import org.demo.bankdemo.payment.RefundRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/refunds")
public class RefundApiController {

    private final org.demo.bankdemo.payment.MerchantPaymentService paymentService;

    public RefundApiController(org.demo.bankdemo.payment.MerchantPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    PaymentResponse refund(
            @org.springframework.web.bind.annotation.RequestAttribute(org.demo.bankdemo.payment.MerchantIdentity.REQUEST_ATTRIBUTE)
            org.demo.bankdemo.payment.MerchantIdentity merchant,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RefundRequest request
    ) {
        var title = "Zwrot " + request.orderId() + ": " + request.reason();
        if (title.length() > 140) {
            title = title.substring(0, 140);
        }
        if (request.originalReferenceNumber() == null || request.originalReferenceNumber().isBlank()) {
            throw new org.demo.bankdemo.payment.PaymentException(
                    "Zwrot wymaga referencji oryginalnej platnosci."
            );
        }
        return paymentService.refund(merchant, idempotencyKey, request, title);
    }
}
