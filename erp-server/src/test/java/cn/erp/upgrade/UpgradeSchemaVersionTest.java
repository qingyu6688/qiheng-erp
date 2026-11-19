package cn.erp.upgrade;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

class UpgradeSchemaVersionTest {
    @Test
    void targetVersionMatchesPackagedMigrations() throws Exception {
        var resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");
        int latest = Arrays.stream(resources)
                .map(resource -> resource.getFilename())
                .mapToInt(filename -> Integer.parseInt(filename.substring(1, filename.indexOf("__"))))
                .max().orElseThrow();
        assertThat(UpgradeMain.TARGET_VERSION).isEqualTo(latest);
    }
}
