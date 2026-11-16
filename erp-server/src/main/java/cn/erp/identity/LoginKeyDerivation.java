package cn.erp.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 登录子密钥使用 HKDF-SHA256，固定输出一个 32 字节分组。 */
final class LoginKeyDerivation {
    private static final String HMAC = "HmacSHA256";
    private static final int KEY_BYTES = 32;

    private LoginKeyDerivation() {}

    static byte[] derive(byte[] masterKey, String purpose) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            // RFC 5869 未提供盐时使用 HashLen 个零字节。
            mac.init(new SecretKeySpec(new byte[KEY_BYTES], HMAC));
            byte[] extracted = mac.doFinal(masterKey);
            mac.init(new SecretKeySpec(extracted, HMAC));
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(new byte[]{1});
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("无法派生登录子密钥", failure);
        }
    }
}
