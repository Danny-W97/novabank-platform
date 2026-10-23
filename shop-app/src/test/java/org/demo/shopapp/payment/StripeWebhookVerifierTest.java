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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={-301,301})
    void rejectsExpiredOrFutureSignedEvents(long offset) throws Exception {
        var now=Instant.parse("2026-10-18T12:00:00Z"); var timestamp=now.getEpochSecond()+offset;
        var payload="synthetic payload";
        var signature=hmac("synthetic-secret",timestamp+"."+payload);
        assertThat(StripeWebhookVerifier.isValid(payload,"t="+timestamp+",v1="+signature,"synthetic-secret",now)).isFalse();
    }

    @Test void acceptsOneValidSignatureDuringRotationButRejectsWrongSecretAndMalformedTimestamp() throws Exception {
        var now=Instant.parse("2026-10-18T12:00:00Z"); var timestamp=now.getEpochSecond();
        var signature=hmac("synthetic-secret",timestamp+".payload");
        var header="t="+timestamp+",v1=invalid,v1="+signature;
        assertThat(StripeWebhookVerifier.isValid("payload",header,"synthetic-secret",now)).isTrue();
        assertThat(StripeWebhookVerifier.isValid("payload",header,"different-synthetic-secret",now)).isFalse();
        assertThat(StripeWebhookVerifier.isValid("payload","t=not-a-number,v1="+signature,"synthetic-secret",now)).isFalse();
        assertThat(StripeWebhookVerifier.isValid("payload",null,"synthetic-secret",now)).isFalse();
    }

    private static String hmac(String secret, String value) throws Exception {
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
