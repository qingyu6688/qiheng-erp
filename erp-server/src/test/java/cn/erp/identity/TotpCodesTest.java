package cn.erp.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpCodesTest {
    // RFC 6238 的 SHA-1 测试种子与测试时间，显示六位验证码。
    private static final String SEED="GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfc6238WindowsAndReplay() {
        assertThat(TotpCodes.code(SEED,59/30)).isEqualTo("287082");
        assertThat(TotpCodes.code(SEED,1_111_111_109L/30)).isEqualTo("081804");
        assertThat(TotpCodes.matchingStep(SEED,"287082",2,0)).isEqualTo(1);
        assertThat(TotpCodes.matchingStep(SEED,"287082",1,1)).isEqualTo(-1);
        assertThat(TotpCodes.matchingStep(SEED,"287082",3,0)).isEqualTo(-1);
        assertThat(TotpCodes.matchingStep(SEED,"287082",0,0)).isEqualTo(1);
        assertThat(TotpCodes.secret()).matches("[A-Z2-7]{32}");
        assertThat(TotpCodes.recovery()).matches("(?:[A-F0-9]{5}-){3}[A-F0-9]{5}");
    }
}
