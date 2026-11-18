package cn.erp.gl;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GlHistoryIsolationTest {
    @Test void onlyAbsoluteLocalTestPathsAreAccepted() throws Exception {
        assertThatThrownBy(()->GlHistoryCapacityIT.real("relative-test")).isInstanceOf(IllegalArgumentException.class);
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            assertThatThrownBy(()->GlHistoryCapacityIT.real("D:relative-test")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->GlHistoryCapacityIT.real("C:/")).isInstanceOf(IllegalArgumentException.class);
        }
        var tmp=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        assertThat(GlHistoryCapacityIT.real(tmp.toString())).isEqualTo(tmp);
    }
}
