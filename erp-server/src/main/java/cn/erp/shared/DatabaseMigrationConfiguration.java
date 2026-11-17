package cn.erp.shared;

import java.util.Arrays;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

/** 迁移连接与运行连接分离；本机空配置仍沿用当前测试或开发数据源。 */
@Configuration(proxyBeanMethods=false)
public class DatabaseMigrationConfiguration {
    record Credentials(String user,String password) {}

    @Bean
    FlywayConfigurationCustomizer migrationCredentials(Environment environment) {
        Credentials credentials=credentials(environment);
        return configuration -> configuration.dataSource(environment.getRequiredProperty("spring.datasource.url"), credentials.user(), credentials.password());
    }

    @Bean
    FlywayMigrationStrategy applicationMigration(Environment environment) {
        boolean production = environment.acceptsProfiles(Profiles.of("prod", "production"));
        return flyway -> {
            if (!production) {
                flyway.migrate();
                return;
            }
            var current = flyway.info().current();
            if (current != null && current.getVersion().compareTo(
                    MigrationVersion.fromVersion(Integer.toString(EnterpriseUpgradeMode.SCHEMA_VERSION))) < 0)
                throw new IllegalStateException("旧 ERP 库必须使用停写升级工具核验后再启动 2.0");
            flyway.migrate();
            var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            if (current == null) {
                jdbc.update("""
                        INSERT INTO upgrade_log(run_id,step,details)
                        VALUES('00000000000000000000000000000000','fresh',JSON_OBJECT('version','2.0.0'))
                        """);
            }
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM upgrade_log WHERE step IN ('verified','fresh'))
                    """, Boolean.class)))
                throw new IllegalStateException("候选库尚未完成升级核验，保持维护状态");
        };
    }

    static Credentials credentials(Environment environment) {
        String runtimeUser=environment.getRequiredProperty("spring.datasource.username");
        String runtimePassword=environment.getRequiredProperty("spring.datasource.password");
        String user=environment.getProperty("DB_MIGRATION_USER", "").strip();
        String password=environment.getProperty("DB_MIGRATION_PASSWORD", "");
        if(user.isEmpty() != password.isEmpty()) throw new IllegalStateException("数据库迁移账号和密码必须同时配置");
        String[] profiles=environment.getActiveProfiles().length==0?environment.getDefaultProfiles():environment.getActiveProfiles();
        boolean production=Arrays.stream(profiles).anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
        if(production) {
            if(user.isEmpty() || user.equals(runtimeUser)) throw new IllegalStateException("生产环境必须配置独立的 DB_MIGRATION_USER 和 DB_MIGRATION_PASSWORD，运行账号不能持有迁移权限");
            String host=environment.getProperty("DB_HOST", "127.0.0.1");
            String mode=environment.getProperty("DB_SSL_MODE", "DISABLED");
            if(!host.equals("127.0.0.1")&&!host.equals("localhost")&&!java.util.Set.of("REQUIRED","VERIFY_CA","VERIFY_IDENTITY").contains(mode)) {
                throw new IllegalStateException("跨主机生产数据库连接必须设置 DB_SSL_MODE=REQUIRED 或更严格的证书校验模式");
            }
            if(!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)) throw new IllegalStateException("生产环境会话 Cookie 必须启用 secure");
            boolean docsEnabled=environment.getProperty("springdoc.api-docs.enabled",Boolean.class,false);
            boolean defaultDocs=environment.getProperty("springdoc.enable-default-api-docs",Boolean.class,true);
            boolean managementDocs=environment.getProperty("springdoc.use-management-port",Boolean.class,false);
            // 生产只启用受授权的开放平台分组生成器，默认和管理端口的直接规范继续关闭。
            if(docsEnabled && (defaultDocs || managementDocs)) throw new IllegalStateException("生产环境不能公开接口调试文档");
        }
        return user.isEmpty()?new Credentials(runtimeUser,runtimePassword):new Credentials(user,password);
    }
}
