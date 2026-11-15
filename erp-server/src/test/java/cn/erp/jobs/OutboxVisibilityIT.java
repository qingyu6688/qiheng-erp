package cn.erp.jobs;

import cn.erp.ErpApplication;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class OutboxVisibilityIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxService events;
    @Autowired BusinessCommands commands;
    @Autowired AuditService audit;
    @Autowired CommandResults results;
    @Autowired ObjectMapper json;
    AccessService access;
    OutboxController controller;
    MockHttpServletRequest request=new MockHttpServletRequest();
    CompanyAccess actor;
    long tenant,company,otherCompany,visible,hidden;
    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'后台事件范围测试')",tenant,"T"+tenant);
        for(long id:List.of(company,otherCompany))jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'事件公司')",id,tenant,id==company?"A":"B");
        visible=event(company);hidden=event(otherCompany);
        actor=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("admin:outbox"));
        access=mock(AccessService.class);when(access.requireCompany(request,"admin:outbox")).thenReturn(actor);
        controller=new OutboxController(access,events,jdbc,commands,audit);
    }
    long event(long companyId) {
        long run=Ids.next();jdbc.update("INSERT INTO reconciliation_run(id,tenant_id,company_id,check_type,status) VALUES(?,?,?,'inventory_balance','error')",run,tenant,companyId);
        long id=events.publish(tenant,"reconciliation",run,0,"reconciliation.requested",Map.of("runId",Long.toString(run)));
        jdbc.update("UPDATE outbox_event SET status='dead',attempts=5 WHERE id=?",id);return id;
    }
    @Test void filtersCompanyAndReplaysOnlyCurrentCompanyWithIdempotency() {
        String page=json.writeValueAsString(controller.list(request,"dead",null,20).data());
        assertThat(page).contains(Long.toString(visible)).doesNotContain(Long.toString(hidden));
        assertThatThrownBy(()->controller.replay(request,hidden,UUID.randomUUID().toString())).isInstanceOf(ApiException.class);
        String key=UUID.randomUUID().toString();controller.replay(request,visible,key);controller.replay(request,visible,key);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,visible)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='outbox.replay'",Long.class,tenant)).isEqualTo(1);
        assertThat(results.get(actor,"outbox.replay",key).get("status")).isEqualTo("succeeded");
        var warehouseOnly=new CompanyAccess(tenant,actor.userId(),company,false,Set.of(Ids.next()),actor.permissions());
        assertThatThrownBy(()->results.get(warehouseOnly,"outbox.replay",key)).isInstanceOf(ApiException.class);
    }
    @Test void warehouseOnlyOperatorsCannotReadOrReplayCompanyEvents() {
        var limited=new CompanyAccess(tenant,actor.userId(),company,false,Set.of(),actor.permissions());
        when(access.requireCompany(request,"admin:outbox")).thenReturn(limited);
        assertThatThrownBy(()->controller.list(request,"dead",null,20)).isInstanceOf(ApiException.class).hasMessageContaining("整家公司");
        assertThatThrownBy(()->controller.replay(request,visible,UUID.randomUUID().toString())).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?",String.class,visible)).isEqualTo("dead");
    }
}
