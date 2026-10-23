package org.demo.bankdemo.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.demo.bankdemo.security.TotpSecretCipher;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TotpRecoveryVerificationCommandTest {
    final ObjectMapper mapper = new ObjectMapper();
    String key(int value) {var bytes=new byte[32];java.util.Arrays.fill(bytes,(byte)value);return Base64.getEncoder().encodeToString(bytes);}

    @Test void decryptsMixedLegacyAndNamedKeysFromSameBackupWithoutMutatingCiphertexts() {
        var old=new TotpSecretCipher(key(1)).encrypt("SYNTHETICSEED");
        var versioned=new TotpSecretCipher("","next="+key(2),"next").encrypt("OTHERSEED");
        var input=mapper.valueToTree(Map.of("keys",Map.of("legacy",key(1),"next",key(2)),"ciphertexts",List.of(old,versioned)));
        assertThat(TotpRecoveryVerificationCommand.verify(input)).isEqualTo(2);
        assertThat(input.get("ciphertexts").get(0).asText()).isEqualTo(old);
    }
    @Test void refusesWrongMissingAndTamperedKeysOrRecords() {
        var encrypted=new TotpSecretCipher(key(1)).encrypt("SYNTHETICSEED");
        for(var keys:List.of(Map.of("legacy",key(2)),Map.of("other",key(1)))) {
            var input=mapper.valueToTree(Map.of("keys",keys,"ciphertexts",List.of(encrypted)));
            assertThatThrownBy(()->TotpRecoveryVerificationCommand.verify(input)).isInstanceOf(IllegalStateException.class);
        }
        for(var value:List.of("corrupt",encrypted.substring(0,encrypted.length()-3)+"AAA")) {
            var input=mapper.valueToTree(Map.of("keys",Map.of("legacy",key(1)),"ciphertexts",List.of(value)));
            assertThatThrownBy(()->TotpRecoveryVerificationCommand.verify(input)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void rejectsEmptyOversizedAndUnexpectedInput() {
        for(var rows:List.of(List.<String>of(),java.util.Collections.nCopies(101,"x"))) {
            var input=mapper.valueToTree(Map.of("keys",Map.of("legacy",key(1)),"ciphertexts",rows));
            assertThatThrownBy(()->TotpRecoveryVerificationCommand.verify(input)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->TotpRecoveryVerificationCommand.verify(mapper.valueToTree(Map.of("keys",Map.of("legacy",key(1)),
                "ciphertexts",List.of("x"),"command","untrusted")))).isInstanceOf(IllegalArgumentException.class);
    }
}
