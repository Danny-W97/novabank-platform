package org.demo.bankdemo.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.demo.bankdemo.security.TotpSecretCipher;
import java.util.ArrayList;
import java.util.HashMap;

/** Bounded stdin-only cryptographic verification. No Spring context, DB, HTTP or plaintext output. */
public final class TotpRecoveryVerificationCommand {
    private TotpRecoveryVerificationCommand() { }

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            var input = System.in.readNBytes(1_048_577);
            if (input.length > 1_048_576) throw new IllegalArgumentException();
            var verified = verify(new ObjectMapper().readTree(input));
            System.out.println("{\"verified\":" + verified + "}");
        } catch (Exception exception) {
            System.err.println("TOTP_RECOVERY_VERIFICATION_FAILED: key or ciphertext could not be verified; values omitted.");
            System.exit(1);
        }
    }

    static int verify(JsonNode input) {
        if (input == null || !input.isObject() || input.size() != 2
                || !input.has("keys") || !input.has("ciphertexts")
                || !input.get("keys").isObject() || input.get("keys").isEmpty() || input.get("keys").size() > 32
                || !input.get("ciphertexts").isArray() || input.get("ciphertexts").isEmpty() || input.get("ciphertexts").size() > 100)
            throw new IllegalArgumentException("Invalid verification envelope.");
        var keys = new HashMap<String, String>();
        input.get("keys").fields().forEachRemaining(entry -> {
            if (!entry.getKey().matches("[A-Za-z0-9_-]{1,32}") || !entry.getValue().isTextual()
                    || entry.getValue().textValue().length() > 128) throw new IllegalArgumentException("Invalid key field.");
            keys.put(entry.getKey(), entry.getValue().textValue());
        });
        var ring = new ArrayList<String>();
        keys.forEach((id, value) -> { if (!"legacy".equals(id)) ring.add(id + "=" + value); });
        var active = keys.keySet().stream().filter(id -> !"legacy".equals(id)).sorted().findFirst().orElse("current");
        var cipher = new TotpSecretCipher(keys.getOrDefault("legacy", ""), String.join(",", ring), active);
        for (var encrypted : input.get("ciphertexts")) {
            if (!encrypted.isTextual() || encrypted.textValue().length() > 4096
                    || cipher.decrypt(encrypted.textValue()).isBlank())
                throw new IllegalArgumentException("Invalid encrypted record.");
        }
        return input.get("ciphertexts").size();
    }
}
