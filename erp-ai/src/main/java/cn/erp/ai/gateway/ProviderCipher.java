package cn.erp.ai.gateway;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import cn.erp.ai.common.AiException;

@Component
class ProviderCipher {
    private static final byte[] INFO = "erp-ai-provider-v1".getBytes(StandardCharsets.UTF_8);
    private final byte[] root;
    private final SecureRandom random = new SecureRandom();

    ProviderCipher(@Value("${erp.ai.provider-key}") String secret) {
        root = secret.getBytes(StandardCharsets.UTF_8);
    }

    byte[] seal(String tenant, long provider, String value) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        byte[] output = crypt(Cipher.ENCRYPT_MODE, tenant, provider, nonce, value.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(1 + nonce.length + output.length).put((byte) 1).put(nonce).put(output).array();
    }

    String open(String tenant, long provider, byte[] value) {
        if (value == null) return "";
        if (value.length < 29 || value[0] != 1) throw unavailable();
        return new String(crypt(Cipher.DECRYPT_MODE, tenant, provider,
                Arrays.copyOfRange(value, 1, 13), Arrays.copyOfRange(value, 13, value.length)), StandardCharsets.UTF_8);
    }

    private byte[] crypt(int mode, String tenant, long provider, byte[] nonce, byte[] value) {
        if (root.length < 32) throw unavailable();
        byte[] key = null;
        try {
            // HKDF-SHA256，子密钥用途与登录配置分开，并把企业、模型服务绑定到密文。
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            var extracted = mac.doFinal(root);
            mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
            Arrays.fill(extracted, (byte) 0);
            mac.update(INFO);
            key = mac.doFinal(new byte[]{1});
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((tenant + ":" + provider).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException error) {
            throw unavailable();
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private AiException unavailable() {
        return new AiException(503, "AI_SECRET_UNAVAILABLE", "模型密钥暂不可用，请联系管理员");
    }
}
