package cn.erp.exporting;

import cn.erp.ErpApplication;
import cn.erp.documents.ObjectFiles;
import cn.erp.documents.ObjectCompensation;
import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.*;
import cn.erp.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.*;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class ExportJobsIT {
    private static final long USER=75801,ROLE=75802;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExportJobs jobs;
    @Autowired ExportAccess access;
    @Autowired ReportService reports;
    @Autowired ObjectFiles files;
    @Autowired ObjectMapper json;
    @Autowired ObjectCompensation compensation;
    private ExportWorker worker;
    private final Set<String> permissions=new HashSet<>();
    @BeforeEach void actor() {
        for(var type:ReportType.values()) { permissions.add(type.permission());permissions.add(type.exportPermission()); }permissions.add("cost:view");
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,70101,'exporter','报表导出经办','不用于登录的测试摘要')",USER);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,70101,'EXPORT','报表导出角色')",ROLE);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(75803,70101,?,?)",USER,ROLE);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(75804,70101,?,'company',70201)",USER);
        long id=75810;for(String permission:permissions) { jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,70101,?,id FROM sys_permission WHERE code=?",id++,ROLE,permission); }
        worker=new ExportWorker(jobs,access,reports,files,json,compensation);
    }
    @AfterEach void removeFiles() {
        for(String key:jdbc.queryForList("SELECT storage_key FROM attachment WHERE tenant_id=70101 AND owner_type='export_job'",String.class)) { files.delete(key); }
    }
    @Test void asynchronousJobStoresSnapshotAndDownloadPreservesPrecisionAndOwnership() {
        var actor=scope(true,Set.of(),permissions);long id=create(actor);
        assertThat(jobs.get(actor,id).get("status")).isEqualTo("pending");
        assertThat(worker.runOne()).isTrue();var ready=jobs.get(actor,id);
        assertThat(ready).containsEntry("status","succeeded").containsKeys("asOf","downloadUrl");assertThat(((Number)ready.get("rowCount")).longValue()).isEqualTo(1L);
        String csv=new String(jobs.download(actor,id).bytes(),StandardCharsets.UTF_8);assertThat(csv).startsWith("\uFEFF").contains("测试仓库","'10.000000","'100.00");
        assertThatThrownBy(()->jobs.get(new CompanyAccess(70101,999,70201,true,Set.of(),permissions),id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->jobs.get(new CompanyAccess(70101,USER,70202,true,Set.of(),permissions),id)).isInstanceOf(ApiException.class);
    }
    @Test void departmentSnapshotSurvivesWorkerExpansionAndDownloadRejectsRevocation() {
        var limited=new CompanyAccess(70101,USER,70201,true,Set.of(),permissions,cn.erp.shared.OrgScope.restricted(Set.of(101L),Set.of(),101));
        long id=create(limited);assertThat(worker.runOne()).isTrue();
        assertThat(jobs.download(limited,id).bytes()).isNotEmpty();
        jdbc.update("INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type) VALUES(70101,70201,?,'self')",ROLE);
        assertThatThrownBy(()->jobs.download(scope(true,Set.of(),permissions),id)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(403));
        jdbc.update("UPDATE org_role_data_scope SET scope_type='all' WHERE tenant_id=70101 AND company_id=70201 AND role_id=?",ROLE);
        assertThat(jobs.download(limited,id).bytes()).isNotEmpty();
    }
    @Test void initialScopeAndCostPermissionAreFrozenDespiteSubsequentExpansion() {
        var withoutCost=new HashSet<>(permissions);withoutCost.remove("cost:view");
        var limited=scope(false,Set.of(70301L),withoutCost);long id=create(limited);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(75901,70101,70201,'NEW','后来获得的仓库')");
        jdbc.update("INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,on_hand_qty) VALUES(75902,70101,70201,75901,71001,77)");
        assertThat(worker.runOne()).isTrue();
        String csv=new String(jobs.download(scope(true,Set.of(),permissions),id).bytes(),StandardCharsets.UTF_8);
        assertThat(csv).contains("测试仓库").doesNotContain("后来获得的仓库","库存成本","100.00");
        assertThatThrownBy(()->jobs.download(scope(false,Set.of(75901L),permissions),id)).isInstanceOf(ApiException.class).hasMessageContaining("收窄");
    }
    @Test void downloadChecksCurrentPermissionAgainAndRestorationAllowsTheUnexpiredFile() {
        var actor=scope(true,Set.of(),permissions);long id=create(actor);worker.runOne();
        revoke("export:inventory_summary");
        assertThatThrownBy(()->jobs.download(actor,id)).isInstanceOf(ApiException.class).hasMessageContaining("权限");
        grant("export:inventory_summary",75905);assertThat(jobs.download(actor,id).bytes()).isNotEmpty();
        var noCost=new HashSet<>(permissions);noCost.remove("cost:view");
        assertThatThrownBy(()->jobs.download(scope(true,Set.of(),noCost),id)).isInstanceOf(ApiException.class).hasMessageContaining("收窄");
        jdbc.update("UPDATE export_job SET expire_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);
        assertThatThrownBy(()->jobs.download(actor,id)).isInstanceOf(ApiException.class).hasMessageContaining("过期");
        for(String key:jobs.expire()) { files.delete(key);jobs.removed(key); }
        assertThat(jobs.get(actor,id).get("status")).isEqualTo("expired");
        assertThatThrownBy(()->jobs.get(scope(false,Set.of(),permissions),id)).isInstanceOf(ApiException.class).hasMessageContaining("收窄");
        assertThatThrownBy(()->jobs.get(scope(true,Set.of(),noCost),id)).isInstanceOf(ApiException.class).hasMessageContaining("收窄");
        assertThat(jobs.list(scope(false,Set.of(),permissions),null,20).get("items")).isEqualTo(List.of());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE id=? AND object_deleted_at IS NOT NULL",Long.class,id)).isEqualTo(1);
    }
    @Test void idempotencyThreeJobLimitDurableLeaseRetryAndTerminalExhaustion() {
        var actor=scope(true,Set.of(),permissions);String key=UUID.randomUUID().toString();
        var input=new ExportJobs.Input("inventory_summary",null);
        assertThat(jobs.create(actor,key,input)).isEqualTo(jobs.create(actor,key,input));create(actor);create(actor);
        assertThatThrownBy(()->create(actor)).isInstanceOf(ApiException.class).hasMessageContaining("3个");
        var first=jobs.claim();assertThat(first).isNotNull();
        var second=jobs.claim();assertThat(second.id()).isNotEqualTo(first.id());
        jobs.failed(first,new IllegalStateException("受控网络中断"));
        assertThat(jobs.get(actor,first.id()).get("status")).isEqualTo("pending");
        var retry=jobs.claim();assertThat(retry.id()).isEqualTo(first.id());
        assertThat(jobs.complete(first,new ExportAccess.Scope(false,Set.of(70301L),true),"不应保存的旧租约", "0".repeat(64),1,1,java.time.Instant.now().toString())).isFalse();
        jdbc.update("UPDATE export_job SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",retry.id());
        var recovered=jobs.claim();assertThat(recovered.id()).isEqualTo(first.id());
        jobs.failed(recovered,new IllegalStateException("持续网络中断"));assertThat(jobs.get(actor,first.id()).get("status")).isEqualTo("failed");
    }
    @Test void executionRevocationCreatesNoFileAndCurrentAclIntersectionCannotLeak() {
        var actor=scope(true,Set.of(),permissions);long id=create(actor);revoke("report:inventory_summary");
        worker.runOne();assertThat(jobs.get(scope(true,Set.of(),permissions),id).get("status")).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE owner_type='export_job' AND owner_id=?",Long.class,id)).isZero();
    }
    private long create(CompanyAccess actor) { return Long.parseLong(jobs.create(actor,UUID.randomUUID().toString(),new ExportJobs.Input("inventory_summary",null)).get("taskId").toString()); }
    private CompanyAccess scope(boolean all,Set<Long> warehouses,Set<String> permissions) { return new CompanyAccess(70101,USER,70201,all,warehouses,permissions); }
    private void revoke(String permission) { jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=70101 AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code=?)",ROLE,permission); }
    private void grant(String permission,long id) { jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,70101,?,id FROM sys_permission WHERE code=?",id,ROLE,permission); }
}
