package cn.erp.identity;

import org.springframework.stereotype.Component;

/** 共享已部署的主密钥，以企业和用途隔离平台密文，不向管理接口返回明文。 */
@Component
public class SecretProtector {
    private final LoginSettingsCipher cipher;
    public SecretProtector(LoginSettingsCipher cipher) {this.cipher=cipher;}
    public String encrypt(long tenant,String purpose,String value) {requireKey();return cipher.encrypt(tenant,"platform:"+purpose,value);}
    public String decrypt(long tenant,String purpose,String value) {requireKey();return cipher.decrypt(tenant,"platform:"+purpose,value);}
    public boolean configured() {return cipher.configured();}
    public String fingerprint(long tenant,String purpose,String value) {requireKey();return cipher.fingerprint(tenant,"platform:"+purpose,value);}
    private void requireKey() {if(!configured()) throw new cn.erp.shared.ApiException(503,"PLATFORM_KEY_UNAVAILABLE","平台加密密钥未配置，请联系管理员配置后重试");}
}
