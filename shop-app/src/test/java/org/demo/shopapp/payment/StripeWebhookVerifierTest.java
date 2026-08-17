package org.demo.shopapp.payment;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StripeWebhookVerifierTest {

    @Test
    void acceptsFreshStripeStyleSignature() throws Exception {
        var timestamp = Instant.now().getEpochSecond();
        var payload = "{\"type\":\"checkout.session.completed\"}";
        var secret = "whsec_test";
        var signature = hmac(secret, timestamp + "." + payload);

        assertThat(StripeWebhookVerifier.isValid(
                payload, "t=" + timestamp + ",v1=" + signature, secret, Instant.ofEpochSecond(timestamp)
        )).isTrue();
    }

    @Test
    void rejectsChangedPayload() throws Exception {
        var timestamp = Instant.now().getEpochSecond();
        var payload = "payload";
        var signature = hmac("secret", timestamp + "." + payload);

        assertThat(StripeWebhookVerifier.isValid(
                "changed", "t=" + timestamp + ",v1=" + signature, "secret", Instant.ofEpochSecond(timestamp)
        )).isFalse();
    }

    private static String hmac(String secret, String value) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
