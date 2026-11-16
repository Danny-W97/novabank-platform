import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic integration-fixture generator only. Never accepts or reads application keys. */
public class RecoveryCipherFixture {
    public static void main(String[] args) throws Exception {
        var random = new SecureRandom(); var old = new byte[32]; var next = new byte[32];
        random.nextBytes(old); random.nextBytes(next);
        var legacy = encrypt(old, "", random); var versioned = encrypt(next, "v1.next.", random);
        System.out.println("{\"keys\":{\"legacy\":\""+Base64.getEncoder().encodeToString(old)+"\",\"next\":\""+
                Base64.getEncoder().encodeToString(next)+"\"},\"ciphertexts\":[\""+legacy+"\",\""+versioned+"\"]}");
    }
    static String encrypt(byte[] key, String prefix, SecureRandom random) throws Exception {
        var iv = new byte[12]; random.nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
        if (!prefix.isEmpty()) cipher.updateAAD(prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return prefix+Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(
                cipher.doFinal("JBSWY3DPEHPK3PXP".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
