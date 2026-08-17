package org.demo.bankdemo.transfer;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransactionReferenceGenerator {

    public String generate() {
        var randomPart = UUID.randomUUID().toString().replace("-", "");
        return "TRX-" + randomPart.substring(0, 28);
    }
}
