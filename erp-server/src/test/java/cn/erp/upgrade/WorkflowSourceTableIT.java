package cn.erp.upgrade;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import static org.assertj.core.api.Assertions.*;

/** 升级待办查询必须按逻辑类型分型，同一实体不能被扫描两遍。 */
@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class WorkflowSourceTableIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired UpgradeWorkflows workflows;
    @Test void adjustmentKindsShareAPhysicalTableAndRemainSeparateDuringUpgradeDiscovery() {
        long tenant=Ids.next(),company=Ids.next(),partner=Ids.next(),warehouse=Ids.next(),purchase=Ids.next(),sale=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'升级来源映射')",tenant,"UP-MAP-"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','映射公司')",company,tenant);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'P','映射往来')",partner,tenant);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','映射仓')",warehouse,tenant,company);
        for(long id:List.of(purchase,sale))jdbc.update("INSERT INTO price_adjustment(id,tenant_id,company_id,adjust_no,kind,partner_id,warehouse_id,business_date,reason,status,owner_user_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,'2026-11-01','来源映射','pending',1,1,1)",
                id,tenant,company,"M-"+id,id==purchase?"purchase":"sales",partner,warehouse);
        assertThat(workflows.types(true)).contains("price_adjustment","sales_price_adjustment");
        for(boolean legacy:List.of(false,true)) {
            var rows=workflows.pending(legacy).stream().filter(row->row.tenantId().equals(Long.toString(tenant))).toList();
            assertThat(rows).hasSize(2);
            assertThat(rows).anySatisfy(row->{assertThat(row.type()).isEqualTo("price_adjustment");assertThat(row.id()).isEqualTo(Long.toString(purchase));});
            assertThat(rows).anySatisfy(row->{assertThat(row.type()).isEqualTo("sales_price_adjustment");assertThat(row.id()).isEqualTo(Long.toString(sale));});
        }
    }
}
