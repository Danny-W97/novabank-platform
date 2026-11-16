package org.demo.bankdemo.security;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class TotpSecretCipherTest {
    private String key(int marker) { var bytes=new byte[32]; java.util.Arrays.fill(bytes,(byte)marker); return Base64.getEncoder().encodeToString(bytes); }
    @Test void rotationReencryptsSameSeedAndCanReadLegacyAndVersionedRecords() {
        var legacy=new TotpSecretCipher(key(1));
        var old=legacy.encrypt("synthetic-seed");
        var first=new TotpSecretCipher(key(1),"first="+key(2),"first");
        assertThat(first.decrypt(old)).isEqualTo("synthetic-seed");
        var next=first.reencrypt(old);
        assertThat(next).startsWith("v1.first.");
        var rotated=new TotpSecretCipher(key(1),"first="+key(2)+",second="+key(3),"second");
        assertThat(rotated.decrypt(next)).isEqualTo("synthetic-seed");
        assertThat(rotated.decrypt(rotated.reencrypt(next))).isEqualTo("synthetic-seed");
        assertThatThrownBy(()->new TotpSecretCipher(key(4)).decrypt(old)).isInstanceOf(IllegalStateException.class);
    }
    @Test void changingVersionedKeyIdInvalidatesAuthenticationTag() {
        var cipher=new TotpSecretCipher("","first="+key(2)+",second="+key(2),"first");
        var encrypted=cipher.encrypt("synthetic-seed");
        assertThatThrownBy(()->cipher.decrypt(encrypted.replace("v1.first.","v1.second.")))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void reviewedPlanFingerprintIncludesCipherFormatAndIgnoresKeyOrder() {
        var legacy=new TotpSecretCipher(key(1));
        var versioned=new TotpSecretCipher("","legacy="+key(1),"legacy");
        assertThat(legacy.configurationFingerprint()).isNotEqualTo(versioned.configurationFingerprint());
        var first=new TotpSecretCipher(key(1),"a="+key(2)+",b="+key(3),"b");
        var reordered=new TotpSecretCipher(key(1),"b="+key(3)+",a="+key(2),"b");
        assertThat(first.configurationFingerprint()).isEqualTo(reordered.configurationFingerprint());
    }
}
