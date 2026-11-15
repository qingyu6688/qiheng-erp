package cn.erp.identity;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cn.erp.shared.ApiException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginSettingsTest {
    @Test
    void encryptedSecretUsesNewNonceAndCannotMoveAcrossTenantOrMethod() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var cipher = new LoginSettingsCipher(Base64.getEncoder().encodeToString(key));
        String secret = "仅用于验证的测试密钥";
        String encrypted = cipher.encrypt(7, "sms", secret);

        assertFalse(encrypted.contains(secret));
        assertNotEquals(encrypted, cipher.encrypt(7, "sms", secret));
        assertEquals(secret, cipher.decrypt(7, "sms", encrypted));
        assertThrows(ApiException.class, () -> cipher.decrypt(8, "sms", encrypted));
        assertThrows(ApiException.class, () -> cipher.decrypt(7, "wechat", encrypted));

        byte[] corrupted = Base64.getDecoder().decode(encrypted.substring(2));
        corrupted[corrupted.length - 1] ^= 1;
        String modified = "1." + Base64.getEncoder().encodeToString(corrupted);
        assertThrows(ApiException.class, () -> cipher.decrypt(7, "sms", modified));
    }

    @Test
    void aliyunSignatureMatchesPublishedAcs3Vector() throws Exception {
        // 固定参数来自阿里云原生 HTTP V3 签名示例。
        var headers = LoginCodeDelivery.smsHeaders("ecs.cn-shanghai.aliyuncs.com", "RunInstances",
                "2014-05-26", "2023-10-26T10:22:32Z", "3156853299f313e23d1673dc12e1703d");
        var query = LoginCodeDelivery.query(Map.of("RegionId", "cn-shanghai",
                "ImageId", "win2019_1809_x64_dtc_zh-cn_40G_alibase_20230811.vhd"));
        String result = LoginCodeDelivery.authorization("YourAccessKeyId", "YourAccessKeySecret", query, headers);

        assertEquals("ACS3-HMAC-SHA256 Credential=YourAccessKeyId,"
                + "SignedHeaders=host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version,"
                + "Signature=06563a9e1b43f5dfe96b81484da74bceab24a1d853912eee15083a6f0f3283c0", result);
        assertEquals("A=%2B%20~&B=%2A", LoginCodeDelivery.query(Map.of("B", "*", "A", "+ ~")));
    }

    @Test
    void smtpDestinationsAreSingleAddressesAndLimitedToApprovedHosts() {
        var settings = new LoginSettingsService(null, null, null, null, "SMTP.EXAMPLE.ORG, relay.example.org");
        assertTrue(settings.smtpAllowed("smtp.example.org"));
        assertTrue(settings.smtpAllowed("relay.example.org"));
        assertFalse(settings.smtpAllowed("smtp.example.org.other.example"));
        assertFalse(settings.smtpAllowed("127.0.0.1"));
        assertTrue(LoginSettingsService.validAddress("operator+test@example.org"));
        assertFalse(LoginSettingsService.validAddress("operator@example.org,other@example.org"));
        assertFalse(LoginSettingsService.validAddress("operator@example.org\r\nCc:other@example.org"));
        assertFalse(LoginSettingsService.validAddress("运维 <operator@example.org>"));
    }
}
