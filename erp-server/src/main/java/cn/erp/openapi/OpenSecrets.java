package cn.erp.openapi;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.erp.identity.LoginKeyDerivation;
import cn.erp.shared.ApiException;

@Component
class OpenSecrets {
    private static final String HMAC = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    OpenSecrets(@Value("${ERP_OPEN_SETTINGS_KEY:}") String openKey,@Value("${ERP_LOGIN_SETTINGS_KEY:}") String loginKey) {
        String encodedKey=openKey.isBlank()?loginKey:openKey;
        byte[] raw=decodeKey(encodedKey);
        key = raw == null ? null : new SecretKeySpec(LoginKeyDerivation.derive(raw,"erp-open-v1"),"AES");
    }

    private static byte[] decodeKey(String value) {
        if (value.isBlank()) return null;
        try {
            byte[] bytes=Base64.getDecoder().decode(value.strip());
            if (bytes.length==KEY_BYTES) return bytes;
        } catch (IllegalArgumentException failure) {
            org.slf4j.LoggerFactory.getLogger(OpenSecrets.class).warn("开放平台根密钥格式无效，应用签名不可用");
            return null;
        }
        org.slf4j.LoggerFactory.getLogger(OpenSecrets.class).warn("开放平台根密钥须解码为 32 字节，应用签名不可用");
        return null;
    }

    String secret() { return Base64.getUrlEncoder().withoutPadding().encodeToString(random(KEY_BYTES)); }
    String appKey() { return "ak_" + HexFormat.of().formatHex(random(16)); }

    String encrypt(long tenant, long id, String kind, String secret) {
        byte[] nonce = random(NONCE_BYTES);
        try {
            byte[] sealed = cipher(Cipher.ENCRYPT_MODE,tenant,id,kind,nonce).doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(NONCE_BYTES+sealed.length).put(nonce).put(sealed).array());
        } catch (GeneralSecurityException failure) { throw unavailable(); }
    }

    String decrypt(long tenant, long id, String kind, String value) {
        try {
            byte[] sealed = Base64.getDecoder().decode(value);
            if (sealed.length <= NONCE_BYTES) throw unavailable();
            byte[] nonce = java.util.Arrays.copyOf(sealed,NONCE_BYTES);
            return new String(cipher(Cipher.DECRYPT_MODE,tenant,id,kind,nonce)
                    .doFinal(sealed,NONCE_BYTES,sealed.length-NONCE_BYTES),StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw unavailable(); }
    }

    static byte[] sign(String secret, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),HMAC));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) { throw new IllegalStateException("无法计算开放平台请求签名",error); }
    }

    private Cipher cipher(int mode, long tenant,long id,String kind,byte[] nonce) throws GeneralSecurityException {
        if (key == null) throw unavailable();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,key,new GCMParameterSpec(128,nonce));
        cipher.updateAAD(("erp-open:"+tenant+":"+kind+":"+id).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private byte[] random(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static ApiException unavailable() {
        return new ApiException(503,"OPEN_KEY_UNAVAILABLE","开放平台凭据尚未配置或不可用");
    }
}
