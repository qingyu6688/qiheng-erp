package cn.erp.upgrade;

import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 仅对本轮新建、尚无表的隔离库执行 V103 到 V104 演练，不清理已有数据库。 */
@EnabledIfEnvironmentVariable(named="ERP_TERMS_UPGRADE_DB", matches="erp_test_terms_upgrade_[a-z0-9]+")
class PaymentTermsMigrationIT {
    @Test void upgradeKeepsExistingDueDatesAndDoesNotGuessLegacyTerms() {
        String database=Objects.requireNonNull(System.getenv("ERP_TERMS_UPGRADE_DB"));
        String host=System.getenv().getOrDefault("DB_HOST","127.0.0.1");
        assertThat(host).isIn("127.0.0.1","localhost");
        String url="jdbc:mysql://"+host+":"+System.getenv("DB_PORT")+"/"+database+"?connectionTimeZone=UTC&characterEncoding=UTF-8";
        var source=new DriverManagerDataSource(url,System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(source).target("103").load().migrate();
        legacy(jdbc);
        var before=jdbc.queryForList("SELECT id,due_date FROM receivable ORDER BY id");
        Flyway.configure().dataSource(source).target("104").load().migrate();
        assertThat(jdbc.queryForList("SELECT id,due_date FROM receivable ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT due_date FROM payable WHERE id=20",java.sql.Date.class))
                .isEqualTo(java.sql.Date.valueOf("2026-12-01"));
        assertThat(jdbc.queryForMap("SELECT term_type,term_value,term_override FROM sales_order WHERE id=30"))
                .containsEntry("term_type",null).containsEntry("term_value",null).containsEntry("term_override",0);
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(104);
    }

    private void legacy(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(1,'UPGRADE-TERM','账期升级演练')");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(1,1,'A','历史公司')");
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(1,1,'P','历史往来单位')");
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(1,1,1,'customer'),(2,1,1,'supplier')");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(1,1,1,'202611','2026-11-01','2026-11-30')");
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,status,revision,approved_at) VALUES(30,1,1,'TERM-OLD',1,'2026-11-01',1,'approved',1,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,due_date,period_id) VALUES(10,1,1,1,'charge','opening_entry',10,10,100,100,0,'2026-11-01',NULL,1),(11,1,1,1,'charge','opening_entry',11,11,100,100,0,'2026-11-01','2026-12-01',1)");
        jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,due_date,period_id) VALUES(20,1,1,1,'charge','opening_entry',20,20,100,100,0,'2026-11-01','2026-12-01',1)");
        jdbc.update("INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,open_amount,aging_date) VALUES(10,1,1,1,'receivable',10,'D',100,100,'2026-11-01'),(11,1,1,1,'receivable',11,'D',100,100,'2026-11-01'),(20,1,1,1,'payable',20,'C',100,100,'2026-11-01')");
    }
}
