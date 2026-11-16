package org.demo.shopapp.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public final class StripeWebhookVerifier {

    private StripeWebhookVerifier() {
    }

    public static boolean isValid(String payload, String signatureHeader, String secret, Instant now) {
        if (payload == null || signatureHeader == null || secret == null || secret.isBlank()) {
            return false;
        }
        long timestamp = 0;
        var expected = new java.util.ArrayList<String>();
        for (String part : signatureHeader.split(",")) {
            var pieces = part.split("=", 2);
            if (pieces.length != 2) continue;
            if ("t".equals(pieces[0])) {
                try { timestamp = Long.parseLong(pieces[1]); } catch (NumberFormatException ignored) { return false; }
            } else if ("v1".equals(pieces[0])) {
                expected.add(pieces[1]);
            }
        }
        if (timestamp == 0 || Math.abs(now.getEpochSecond() - timestamp) > 300 || expected.isEmpty()) {
            return false;
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var actual = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return expected.stream().anyMatch(value -> MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.US_ASCII), value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            return false;
        }
    }
}
