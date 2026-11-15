package cn.erp.e2e;

import cn.erp.shared.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 只准备可安全重放的空对账事件，浏览器负责重放与审计查询。 */
@Component
@Profile("dev & e2e & !prod & !production")
public class AuditBrowserFixture {
    private final JdbcTemplate jdbc;
    private final String tenantCode,password,databaseUrl;
    public AuditBrowserFixture(JdbcTemplate jdbc,@Value("${ERP_E2E_TENANT_CODE:}") String tenantCode,
            @Value("${ERP_E2E_PASSWORD:}") String password,@Value("${spring.datasource.url}") String databaseUrl) {
        this.jdbc=jdbc;this.tenantCode=tenantCode;this.password=password;this.databaseUrl=databaseUrl;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prepareAuditEvent() {
        if(!tenantCode.matches("E2E-[a-f0-9]{12}")||password.length()<16||
                !databaseUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/erp_test(?:_[a-z0-9]+)*(?:\\?.*)?")) {
            throw new IllegalStateException("审计验收夹具仅允许本机独立测试库、随机测试企业和临时口令");
        }
        long tenant=jdbc.queryForObject("SELECT id FROM tenant WHERE code=?",Long.class,tenantCode);
        long company=jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND code='DEMO-A'",Long.class,tenant);
        long user=jdbc.queryForObject("SELECT id FROM sys_user WHERE tenant_id=? AND username='admin'",Long.class,tenant);
        long run=Ids.next();jdbc.update("INSERT INTO reconciliation_run(id,tenant_id,company_id,check_type,status,triggered_by) VALUES(?,?,?,'inventory_balance','error',?)",run,tenant,company,user);
        jdbc.update("INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,status,attempts,last_error) VALUES(?,?,'reconciliation',?,0,'reconciliation.requested',?,'dead',5,'浏览器验收待重放')",
                Ids.next(),tenant,run,"{\"runId\":\""+run+"\"}");
    }
}
