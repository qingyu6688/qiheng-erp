package cn.erp.shared;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DatabaseMigrationConfigurationTest {
    private MockEnvironment environment(String... profiles) {
        var environment=new MockEnvironment().withProperty("spring.datasource.username","runtime").withProperty("spring.datasource.password","运行口令");
        environment.setActiveProfiles(profiles);return environment;
    }
    @Test void developmentAndTestBlankSettingsUseTheirOwnDataSource() {
        for(String profile:new String[]{"dev","test"}) {
            var environment=environment(profile).withProperty("DB_MIGRATION_USER","").withProperty("DB_MIGRATION_PASSWORD","");
            assertThat(DatabaseMigrationConfiguration.credentials(environment).user()).isEqualTo("runtime");
            assertThat(DatabaseMigrationConfiguration.credentials(environment).password()).isEqualTo("运行口令");
        }
    }
    @Test void productionRequiresAnIndependentMigrationIdentityAndSecureSettings() {
        var environment=environment("prod");
        assertThatThrownBy(() -> DatabaseMigrationConfiguration.credentials(environment)).hasMessageContaining("独立");
        environment.withProperty("DB_MIGRATION_USER","migration").withProperty("DB_MIGRATION_PASSWORD","迁移口令");
        assertThat(DatabaseMigrationConfiguration.credentials(environment).user()).isEqualTo("migration");
        environment.withProperty("DB_HOST","db.internal");
        assertThatThrownBy(() -> DatabaseMigrationConfiguration.credentials(environment)).hasMessageContaining("DB_SSL_MODE");
        environment.withProperty("DB_SSL_MODE","VERIFY_IDENTITY");
        assertThat(DatabaseMigrationConfiguration.credentials(environment).user()).isEqualTo("migration");
        environment.withProperty("server.servlet.session.cookie.secure","false");
        assertThatThrownBy(() -> DatabaseMigrationConfiguration.credentials(environment)).hasMessageContaining("Cookie");
    }
    @Test void malformedPairsAndMixedProductionProfilesDoNotSilentlyFallBack() {
        var environment=environment("test","production").withProperty("DB_MIGRATION_USER","migration");
        assertThatThrownBy(() -> DatabaseMigrationConfiguration.credentials(environment)).hasMessageContaining("同时配置");
        environment.withProperty("DB_MIGRATION_PASSWORD","迁移口令").withProperty("DB_MIGRATION_USER","runtime");
        assertThatThrownBy(() -> DatabaseMigrationConfiguration.credentials(environment)).hasMessageContaining("独立");
    }
}
