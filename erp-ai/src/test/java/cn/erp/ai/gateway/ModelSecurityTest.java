package cn.erp.ai.gateway;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import cn.erp.ai.common.AiException;

class ModelSecurityTest {
    @Test void onlyDeploymentApprovedEndpointsAreAllowedAndOverseasNeedsBothApprovals() {
        var local=new ProviderEndpoints("onsite=http://127.0.0.1:8000/v1,claude=https://api.anthropic.com", false);
        assertThat(local.require("onsite","openai_compatible","domestic",false).toString())
                .isEqualTo("http://127.0.0.1:8000/v1");
        assertThatThrownBy(() -> local.require("https://169.254.169.254","openai_compatible","domestic",false))
                .isInstanceOf(AiException.class);
        assertThatThrownBy(() -> local.require("claude","anthropic","overseas",true)).isInstanceOf(AiException.class);
        assertThatThrownBy(() -> local.require("claude","openai_compatible","domestic",false)).isInstanceOf(AiException.class);
        var external=new ProviderEndpoints("claude=https://api.anthropic.com",true);
        assertThatThrownBy(() -> external.require("claude","anthropic","overseas",false)).isInstanceOf(AiException.class);
        assertThatThrownBy(() -> external.require("claude","anthropic","domestic",true)).isInstanceOf(AiException.class);
        assertThat(external.require("claude","anthropic","overseas",true).getHost()).isEqualTo("api.anthropic.com");
        assertThatThrownBy(() -> new ProviderEndpoints("bad=https://user@dev.invalid/v1",false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void providerSecretIsBoundToTenantAndProviderAndRejectsTampering() {
        var cipher=new ProviderCipher("测试专用主密钥012345678901234567890123456789");
        var first=cipher.seal("100",4,"test-fixture-secret");
        var second=cipher.seal("100",4,"test-fixture-secret");
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.open("100",4,first)).isEqualTo("test-fixture-secret");
        assertThatThrownBy(() -> cipher.open("101",4,first)).isInstanceOf(AiException.class);
        assertThatThrownBy(() -> cipher.open("100",5,first)).isInstanceOf(AiException.class);
        first[first.length-1]^=1;
        assertThatThrownBy(() -> cipher.open("100",4,first)).isInstanceOf(AiException.class);
    }
}
