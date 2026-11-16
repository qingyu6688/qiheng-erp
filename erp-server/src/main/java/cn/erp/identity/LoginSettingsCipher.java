package cn.erp.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.erp.shared.ApiException;

@Component
class LoginSettingsCipher {
    private static final Logger LOG = LoggerFactory.getLogger(LoginSettingsCipher.class);
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String ENVELOPE_VERSION = "2.";
    private static final String LEGACY_VERSION = "1.";
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;
    private final SecretKeySpec legacyKey;

    LoginSettingsCipher(@Value("${ERP_LOGIN_SETTINGS_KEY:}") String encodedKey) {
        legacyKey = loadKey(encodedKey);
        key = legacyKey == null ? null : new SecretKeySpec(
                LoginKeyDerivation.derive(legacyKey.getEncoded(), "erp-login-cipher-v1"), "AES");
    }

    boolean configured() {
        return key != null;
    }

    String fingerprint(long tenant,String purpose,String value) {
        if(key==null) throw unavailable();
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getEncoded(),"HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal((tenant+":"+purpose+":"+value).getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException error) {throw unavailable();}
    }

    String encrypt(long tenantId, String method, String secret) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, key, tenantId, method, nonce)
                    .doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ENVELOPE_VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException failure) {
            throw unavailable();
        }
    }

    String decrypt(long tenantId, String method, String encrypted) {
        if (encrypted == null || !(encrypted.startsWith(ENVELOPE_VERSION) || legacy(encrypted))) {
            throw unavailable();
        }
        try {
            byte[] value = Base64.getDecoder().decode(encrypted.substring(ENVELOPE_VERSION.length()));
            if (value.length <= NONCE_BYTES + TAG_BITS / Byte.SIZE) throw unavailable();
            byte[] nonce = new byte[NONCE_BYTES];
            ByteBuffer.wrap(value).get(nonce);
            byte[] clear = cipher(Cipher.DECRYPT_MODE, legacy(encrypted) ? legacyKey : key, tenantId, method, nonce)
                    .doFinal(value, NONCE_BYTES, value.length - NONCE_BYTES);
            return new String(clear, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    boolean legacy(String encrypted) {
        return encrypted != null && encrypted.startsWith(LEGACY_VERSION);
    }

    private Cipher cipher(int operation, SecretKeySpec cipherKey, long tenantId, String method, byte[] nonce)
            throws GeneralSecurityException {
        if (cipherKey == null) throw unavailable();
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(operation, cipherKey, new GCMParameterSpec(TAG_BITS, nonce));
        // 将密文固定在企业及登录方式上，禁止跨配置复制使用。
        cipher.updateAAD(("erp-login:" + tenantId + ":" + method).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private SecretKeySpec loadKey(String value) {
        if (value.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(value.strip());
            if (bytes.length == KEY_BYTES) return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException failure) {
            LOG.warn("登录设置密钥的 Base64 格式无效，扩展登录方式不可用");
            return null;
        }
        LOG.warn("登录设置密钥必须解码为 32 字节，扩展登录方式不可用");
        return null;
    }

    private ApiException unavailable() {
        return new ApiException(503, "LOGIN_SETTINGS_UNAVAILABLE", "登录方式配置暂不可用，请联系管理员");
    }
}
