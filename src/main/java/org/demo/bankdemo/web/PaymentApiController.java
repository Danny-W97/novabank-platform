package org.demo.bankdemo.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
public class PaymentApiController {

    @PostMapping
    void pay() {
        throw new ResponseStatusException(HttpStatus.GONE,
                "Bezpośrednie obciążenie rachunku jest wyłączone. Użyj autoryzacji płatności.");
    }
}
