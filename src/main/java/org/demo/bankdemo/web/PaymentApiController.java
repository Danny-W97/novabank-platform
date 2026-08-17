package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.payment.PaymentRequest;
import org.demo.bankdemo.payment.PaymentResponse;
import org.demo.bankdemo.payment.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentApiController {

    private final PaymentService paymentService;

    public PaymentApiController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    PaymentResponse pay(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request
    ) {
        return paymentService.execute(idempotencyKey, request);
    }
}
