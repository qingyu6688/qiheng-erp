package cn.erp.identity;

import org.springframework.stereotype.Component;

/** 利用已有企业密钥保护业务私密字段，按公司、用途及对象绑定密文。 */
@Component
public class ScopedPrivateValues {
    private final LoginSettingsCipher cipher;

    ScopedPrivateValues(LoginSettingsCipher cipher) {
        this.cipher = cipher;
    }

    public String seal(CompanyAccess actor, String field, long objectId, String value) {
        return cipher.encrypt(actor.tenantId(), purpose(actor, field) + ":" + objectId, value);
    }

    public String open(CompanyAccess actor, String field, long objectId, String encoded) {
        return cipher.decrypt(actor.tenantId(), purpose(actor, field) + ":" + objectId, encoded);
    }

    public String fingerprint(CompanyAccess actor, String field, String value) {
        return cipher.fingerprint(actor.tenantId(), purpose(actor, field), value);
    }

    private String purpose(CompanyAccess actor, String field) {
        return "private:" + actor.companyId() + ":" + field;
    }
}
