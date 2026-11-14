package cn.erp.identity;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import cn.erp.shared.ApiException;

import static org.junit.jupiter.api.Assertions.*;

class IdentityRulesTest {
    @Test
    void 会话绝对有效期为十二小时() {
        Instant login = Instant.parse("2026-01-01T00:00:00Z");
        var account = new SessionAccount(1, 2, 0, login.toEpochMilli());
        assertFalse(account.isExpired(login.plusSeconds(12 * 3600 - 1)));
        assertTrue(account.isExpired(login.plusSeconds(12 * 3600)));
    }

    @Test
    void 密码长度按字符下限和字节上限校验() {
        assertThrows(ApiException.class, () -> CredentialsService.validateNewPassword("短密码"));
        assertThrows(ApiException.class, () -> CredentialsService.validateNewPassword("账".repeat(25)));
        assertDoesNotThrow(() -> CredentialsService.validateNewPassword("账".repeat(24)));
    }

    @Test
    void 局部仓库和整公司范围分别生效() {
        var partly = new CompanyAccess(1, 2, 3, false, Set.of(4L), Set.of("warehouse:view"));
        assertTrue(partly.allowsWarehouse(4));
        assertFalse(partly.allowsWarehouse(5));
        assertFalse(partly.hasPermission("warehouse:update"));
        assertTrue(new CompanyAccess(1, 2, 3, true, Set.of(), Set.of()).allowsWarehouse(5));
    }
}
