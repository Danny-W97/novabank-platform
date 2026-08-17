package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.payment.PaymentRequest;
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

    private final PaymentService paymentService;

    public RefundApiController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    PaymentResponse refund(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RefundRequest request
    ) {
        var title = "Zwrot " + request.orderId() + ": " + request.reason();
        if (title.length() > 140) {
            title = title.substring(0, 140);
        }
        if (request.customerAccountNumber() != null && !request.customerAccountNumber().isBlank()) {
            return paymentService.execute(
                    idempotencyKey,
                    new PaymentRequest(
                            request.merchantAccountNumber(),
                            request.customerAccountNumber(),
                            "refund:" + request.orderId(),
                            request.amount(),
                            request.currency(),
                            title
                    )
            );
        }
        if (request.originalReferenceNumber() == null || request.originalReferenceNumber().isBlank()) {
            throw new org.demo.bankdemo.payment.PaymentException(
                    "Brak rachunku klienta lub referencji oryginalnej platnosci."
            );
        }
        return paymentService.refundByReference(
                idempotencyKey,
                request.originalReferenceNumber(),
                request.merchantAccountNumber(),
                request.orderId(),
                request.amount(),
                request.currency(),
                title
        );
    }
}
