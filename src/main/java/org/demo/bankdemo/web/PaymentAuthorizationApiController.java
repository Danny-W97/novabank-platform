package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.payment.authorization.PaymentAuthorizationRequest;
import org.demo.bankdemo.payment.authorization.PaymentAuthorizationResponse;
import org.demo.bankdemo.payment.authorization.PaymentAuthorizationRedeemRequest;
import org.demo.bankdemo.payment.authorization.PaymentAuthorizationRedeemResponse;
import org.demo.bankdemo.payment.authorization.PaymentAuthorizationService;
import org.demo.bankdemo.payment.authorization.PaymentAuthorizationStatusResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.demo.bankdemo.payment.MerchantIdentity;

@RestController
@RequestMapping("/api/payment-authorizations")
public class PaymentAuthorizationApiController {

    private final PaymentAuthorizationService service;

    public PaymentAuthorizationApiController(PaymentAuthorizationService service) {
        this.service = service;
    }

    @PostMapping
    PaymentAuthorizationResponse create(@RequestAttribute(MerchantIdentity.REQUEST_ATTRIBUTE) MerchantIdentity merchant,
                                        @Valid @RequestBody PaymentAuthorizationRequest request) {
        return service.create(merchant, request);
    }

    @PostMapping("/{authorizationId}/redeem")
    PaymentAuthorizationRedeemResponse redeem(
            @RequestAttribute(MerchantIdentity.REQUEST_ATTRIBUTE) MerchantIdentity merchant,
            @PathVariable String authorizationId,
            @Valid @RequestBody PaymentAuthorizationRedeemRequest request
    ) {
        return service.redeem(merchant, authorizationId, request);
    }

    @GetMapping("/{authorizationId}/status")
    PaymentAuthorizationStatusResponse status(@RequestAttribute(MerchantIdentity.REQUEST_ATTRIBUTE) MerchantIdentity merchant,
                                              @PathVariable String authorizationId) {
        return service.status(merchant, authorizationId);
    }

    @GetMapping("/by-key/{key}/status")
    PaymentAuthorizationStatusResponse statusByKey(
            @RequestAttribute(MerchantIdentity.REQUEST_ATTRIBUTE) MerchantIdentity merchant,
            @PathVariable String key) {
        return service.statusByKey(merchant, key);
    }

    @PostMapping("/by-key/{key}/cancel")
    PaymentAuthorizationStatusResponse cancelByKey(
            @RequestAttribute(MerchantIdentity.REQUEST_ATTRIBUTE) MerchantIdentity merchant,@PathVariable String key) {
        return service.cancelByKey(merchant,key);
    }
}
