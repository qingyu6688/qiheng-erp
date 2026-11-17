package cn.erp.upgrade;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSessionRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name = "erp.upgrade.cli", havingValue = "true")
class UpgradeConfiguration {
    @Bean
    MapSessionRepository upgradeSessions() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }
}
