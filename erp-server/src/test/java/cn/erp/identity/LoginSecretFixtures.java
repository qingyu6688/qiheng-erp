package cn.erp.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class LoginSecretFixtures {
    private LoginSecretFixtures() {}

    static String legacySecret(byte[] masterKey, long tenantId, String method, String secret)
            throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("erp-login:" + tenantId + ":" + method).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        return "1." + Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
    }
}
