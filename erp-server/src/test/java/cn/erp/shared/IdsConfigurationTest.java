package cn.erp.shared;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IdsConfigurationTest {
    @Test void trimsLineEndingsAndDefaultsBlankValues() {
        assertThat(Ids.parseNode("0\r")).isZero();
        assertThat(Ids.parseNode(" 3 ")).isEqualTo(3);
        assertThat(Ids.parseNode("\r\n ")).isZero();
        assertThat(Ids.parseNode(null)).isZero();
        assertThat(Ids.parseNode("1023")).isEqualTo(1023);
    }
    @Test void rejectsMalformedAndOutOfRangeConfigurationInChinese() {
        for(String value : new String[]{"abc", "-1", "1024", "999999999999999999999"}) {
            assertThatThrownBy(() -> Ids.parseNode(value)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ERP_NODE_ID 须为 0 到 1023 之间的整数").hasMessageContaining(value);
        }
    }
}
