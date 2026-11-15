package cn.erp.operations;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class OperationsIT {
    @Autowired OperationsService service;
    @Autowired JdbcTemplate jdbc;
    @Test void queuesStayWithinCurrentCompanyAndWarningsReflectRealBacklog() {
        long tenant=Ids.next(),company=Ids.next(),other=Ids.next(),user=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'运维状态验收')",tenant,"OPS"+tenant);
        for(long id:List.of(company,other))jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'运维公司')",id,tenant,id==company?"A":"B");
        for(long id:List.of(company,other)) {
            long run=Ids.next();jdbc.update("INSERT INTO reconciliation_run(id,tenant_id,company_id,check_type,status) VALUES(?,?,?,'inventory_balance','error')",run,tenant,id);
            jdbc.update("INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,status,attempts,created_at) VALUES(?,?,'reconciliation',?,0,'reconciliation.requested','{}',?,5,DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE))",Ids.next(),tenant,run,id==company?"pending":"dead");
        }
        var actor=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of("admin:outbox"));
        var result=service.status(actor);
        var queues=(Map<?,?>)result.get("queues");
        var outbox=(Map<?,?>)queues.get("outbox");
        assertThat(((Number)outbox.get("pending_count")).longValue()).isEqualTo(1);
        assertThat(((Number)outbox.get("failed_count")).longValue()).isZero();
        assertThat(result.get("warnings").toString()).contains("五分钟").doesNotContain("失败任务");
    }
    @Test void requiresPermissionAndWholeCompanyScope() {
        assertThatThrownBy(() -> service.status(new CompanyAccess(1,2,3,false,Set.of(4L),Set.of("admin:outbox")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.status(new CompanyAccess(1,2,3,true,Set.of(),Set.of()))).isInstanceOf(ApiException.class);
    }
}
