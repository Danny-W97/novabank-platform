package org.demo.bankdemo.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class TotpSecretCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final String encodedKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public TotpSecretCipher(@Value("${app.security.totp-encryption-key:}") String encodedKey) {
        this.encodedKey = encodedKey;
    }

    public String encrypt(String value) {
        try {
            var iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH, iv));
            var encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + "."
                    + Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Nie można zaszyfrować sekretu TOTP.", exception);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Brak sekretu TOTP.");
        }
        try {
            var parts = value.split("\\.", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Nieprawidłowy format sekretu TOTP.");
            }
            var iv = Base64.getDecoder().decode(parts[0]);
            var encrypted = Base64.getDecoder().decode(parts[1]);
            var cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Nie można odszyfrować sekretu TOTP.", exception);
        }
    }

    private SecretKeySpec key() {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalStateException("Brak TOTP_ENCRYPTION_KEY.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY musi być kluczem Base64.", exception);
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalStateException("TOTP_ENCRYPTION_KEY musi mieć 16, 24 albo 32 bajty.");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
