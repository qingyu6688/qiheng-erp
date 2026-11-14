package cn.erp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulesTest {
    @Test
    void modulesDependOnlyOnPublicInterfaces() {
        ApplicationModules.of(ErpApplication.class).verify();
    }
}
