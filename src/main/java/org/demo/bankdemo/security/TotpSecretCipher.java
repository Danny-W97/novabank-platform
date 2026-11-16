package org.demo.bankdemo.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TotpSecretCipher {
    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    private final String activeId;
    private final boolean versioned;
    private final SecureRandom random = new SecureRandom();

    public TotpSecretCipher(String legacyKey) { this(legacyKey, "", "current"); }

    @Autowired
    public TotpSecretCipher(@Value("${app.security.totp-encryption-key:}") String legacyKey,
            @Value("${app.security.totp-encryption-keys:}") String keyRing,
            @Value("${app.security.totp-encryption-active-id:current}") String activeId) {
        versioned = keyRing != null && !keyRing.isBlank();
        if (legacyKey != null && !legacyKey.isBlank()) keys.put("legacy", decodeKey(legacyKey));
        if (versioned) {
            for (var entry : keyRing.split(",", -1)) {
                var pair = entry.split("=", 2);
                if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_-]{1,32}")
                        || keys.putIfAbsent(pair[0], decodeKey(pair[1])) != null)
                    throw new IllegalStateException("Invalid or duplicate TOTP key ID.");
            }
        }
        this.activeId = versioned ? activeId : "legacy";
        if (versioned && !keys.containsKey(activeId))
            throw new IllegalStateException("Active TOTP key ID is absent from key ring.");
    }

    public String encrypt(String value) {
        try {
            var iv = new byte[12];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requiredKey(activeId), new GCMParameterSpec(128, iv));
            var prefix = versioned ? "v1." + activeId + "." : "";
            if (versioned) cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
            return prefix + Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Cannot encrypt TOTP seed; check key configuration.", exception); }
    }

    public String decrypt(String value) {
        try {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing ciphertext.");
            var parts = value.split("\\.", -1);
            String id;
            String iv;
            String encrypted;
            String prefix = null;
            if (parts.length == 2) { id = "legacy"; iv = parts[0]; encrypted = parts[1]; }
            else if (parts.length == 4 && "v1".equals(parts[0])) {
                id = parts[1]; iv = parts[2]; encrypted = parts[3]; prefix = "v1." + id + ".";
            } else throw new IllegalArgumentException("Invalid ciphertext format.");
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requiredKey(id),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(iv)));
            if (prefix != null) cipher.updateAAD(prefix.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("Cannot decrypt TOTP seed; keep the previous key available.", exception); }
    }

    public String reencrypt(String ciphertext) { return encrypt(decrypt(ciphertext)); }
    public String activeKeyId() { return activeId; }

    /** Opaque configuration binding for reviewed rotation plans; never returns key bytes. */
    public String configurationFingerprint() {
        requiredKey(activeId);
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update((versioned ? "versioned:" : "legacy:").getBytes(StandardCharsets.UTF_8));
            digest.update((activeId.length()+":"+activeId+"\n").getBytes(StandardCharsets.UTF_8));
            for (var id : keys.keySet().stream().sorted().toList()) {
                var encoded=keys.get(id).getEncoded();
                digest.update((id.length()+":"+id+":"+encoded.length+":").getBytes(StandardCharsets.UTF_8));
                digest.update(encoded);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public boolean isCurrent(String ciphertext) {
        return ciphertext != null && (versioned ? ciphertext.startsWith("v1." + activeId + ".")
                : ciphertext.split("\\.", -1).length == 2);
    }

    private SecretKeySpec requiredKey(String id) {
        var key = keys.get(id);
        if (key == null) throw new IllegalStateException("Required TOTP key ID is unavailable.");
        return key;
    }

    private static SecretKeySpec decodeKey(String encoded) {
        try {
            var bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32)
                throw new IllegalArgumentException("Invalid AES key length.");
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException exception) { throw new IllegalStateException("Invalid TOTP key encoding or length."); }
    }
}
