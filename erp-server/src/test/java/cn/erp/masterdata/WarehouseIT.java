package cn.erp.masterdata;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class WarehouseIT {
    private static final Set<String> PERMISSIONS = Set.of("warehouse:view", "warehouse:create", "warehouse:update");
    @Autowired WarehouseService service;
    @Autowired JdbcTemplate jdbc;
    private CompanyAccess all;
    private long otherCompany;

    @BeforeEach
    void setUp() {
        long tenant = Ids.next();
        long company = Ids.next();
        otherCompany = Ids.next();
        all = new CompanyAccess(tenant, Ids.next(), company, true, Set.of(), PERMISSIONS);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)", tenant, "T" + tenant, "测试租户");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?),(?,?,?,?)",
                company, tenant, "C1", "测试公司一", otherCompany, tenant, "C2", "测试公司二");
    }

    @Test
    void createEditAndDisableKeepVersionAndAudit() {
        var created = create("A");
        long id = Long.parseLong(created.id());
        assertThat(created.version()).isZero();
        assertThat(created.allowedActions()).containsExactly("update", "disable");
        var edited = service.edit(all, id, new WarehouseModels.Edit("A2", "已调整", "新地址", 0L));
        assertThat(edited.version()).isOne();
        assertThat(edited.name()).isEqualTo("已调整");
        String key = key();
        var disabled = service.changeStatus(all, id, "disable", key, 1);
        assertThat(disabled.status()).isEqualTo("disabled");
        assertThat(disabled.allowedActions()).contains("enable");
        assertThat(service.changeStatus(all, id, "disable", key, 1)).isEqualTo(disabled);
        var enabled = service.changeStatus(all, id, "enable", key(), 2);
        assertThat(enabled.status()).isEqualTo("active");
        assertThat(service.result(all, "warehouse.disable", key).response()).isEqualTo(disabled);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE object_id=? ORDER BY id", String.class, id))
                .containsExactly("warehouse.create", "warehouse.update", "warehouse.disable", "warehouse.enable");
    }

    @Test
    void retriesReturnOriginalAndKeyBelongsToOriginalOperator() {
        String key = key();
        var input = new WarehouseModels.Create("A", "测试仓库", null);
        var created = service.create(all, key, input);
        assertThat(service.create(all, key, input)).isEqualTo(created);
        assertThat(service.result(all, "warehouse.create", key).response()).isEqualTo(created);
        var other = new CompanyAccess(all.tenantId(), Ids.next(), all.companyId(), true, Set.of(), PERMISSIONS);
        assertThatThrownBy(() -> service.result(other, "warehouse.create", key))
                .isInstanceOf(ApiException.class).hasMessageContaining("原操作人");
        assertThatThrownBy(() -> service.create(all, key, new WarehouseModels.Create("B", input.name(), null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("不同内容");
        assertThatThrownBy(() -> service.create(all, null, input))
                .isInstanceOf(ApiException.class).hasMessageContaining("缺少幂等键");
    }

    @Test
    void queriesRespectScopeFilterAndCursor() {
        var first = create("A");
        var second = create("B");
        var third = create("C");
        var page = service.list(all, null, "active", 2, null, true);
        assertThat(page.items()).containsExactly(third, second);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.totalExact()).isTrue();
        assertThat(service.list(all, null, "active", 2, page.nextCursor(), false).items()).containsExactly(first);
        var scoped = new CompanyAccess(all.tenantId(), all.userId(), all.companyId(), false,
                Set.of(Long.valueOf(first.id())), Set.of("warehouse:view"));
        var visible = service.list(scoped, null, null, 20, null, true);
        assertThat(visible.items()).extracting(WarehouseModels.View::id).containsExactly(first.id());
        assertThat(visible.items().getFirst().allowedActions()).isEmpty();
        assertThat(service.list(all, "仓库B", null, 20, null, false).items()).containsExactly(second);
        assertThatThrownBy(() -> service.get(scoped, Long.parseLong(second.id()))).isInstanceOf(ApiException.class);
        var another = new CompanyAccess(all.tenantId(), all.userId(), otherCompany, true, Set.of(), PERMISSIONS);
        assertThatThrownBy(() -> service.get(another, Long.parseLong(first.id()))).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectConflictingVersionAndDuplicateCode() {
        var created = create("A");
        assertThatThrownBy(() -> service.edit(all, Long.parseLong(created.id()),
                new WarehouseModels.Edit("A", "同名", null, 5L)))
                .isInstanceOf(ApiException.class).hasMessageContaining("已被他人修改");
        assertThatThrownBy(() -> create("A")).isInstanceOf(ApiException.class).hasMessageContaining("编码已存在");
        assertThat(service.get(all, Long.parseLong(created.id())).version()).isZero();
        assertThatThrownBy(() -> service.list(all, null, null, 10, "错误游标", false))
                .isInstanceOf(ApiException.class).hasMessageContaining("游标无效");
    }

    private WarehouseModels.View create(String code) {
        return service.create(all, key(), new WarehouseModels.Create(code, "仓库" + code, null));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
