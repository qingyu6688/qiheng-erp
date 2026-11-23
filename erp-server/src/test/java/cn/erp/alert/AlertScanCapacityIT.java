package cn.erp.alert;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="ERP_ALERT_CAPACITY", matches="true")
class AlertScanCapacityIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AlertRules rules;
    @Autowired AlertEvents events;
    @Autowired AlertScan scanner;

    private final Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final LocalDate today=LocalDate.ofInstant(now,AlertRules.ZONE);
    private long tenant,company,companyTwo,otherTenant,otherCompany,warehouse,sku,batch,user,partner,openItem,period;
    private CompanyAccess actor;

    private void seed() {
        tenant=createTenant();otherTenant=createTenant();company=createCompany(tenant,"A");companyTwo=createCompany(tenant,"B");
        otherCompany=createCompany(otherTenant,"C");warehouse=Ids.next();sku=Ids.next();batch=Ids.next();user=Ids.next();
        partner=Ids.next();period=Ids.next();openItem=Ids.next();
        long unit=Ids.next(),category=Ids.next(),product=Ids.next(),role=Ids.next();
        actor=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of("alert:view","alert:configure","alert:handle","batch:view","report:receivable_aging","report:payable_aging"));
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'A','主仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'A','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'A','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,batch_managed) VALUES(?,?,?,'A','批次商品',1)",sku,tenant,product);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'tester','预警员','test-hash')",user,tenant);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'SYSTEM_ADMIN','管理员')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,company_id,scope_type) VALUES(?,?,?,?,'company')",Ids.next(),tenant,user,company);
        for (String permission : actor.permissions()) jdbc.update("""
                INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?
                """,Ids.next(),tenant,role,permission);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'CUST','应收客户')",partner,tenant);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611',?,?)",
                period,tenant,company,today.minusDays(90),today.plusDays(30));
        jdbc.update("""
                INSERT INTO inv_batch(id,tenant_id,company_id,sku_id,batch_no,expiry_date,source_type,source_id)
                VALUES(?,?,?,?,'EXPIRED',?,'opening_entry',1)
                """,batch,tenant,company,sku,today.minusDays(2));
        jdbc.update("INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,lot_key,on_hand_qty) VALUES(?,?,?,?,?,?,5)",
                Ids.next(),tenant,company,warehouse,sku,Long.toString(batch));
        long receivable=Ids.next();
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,due_date,period_id)
                VALUES(?,?,?,?,'charge','opening_entry',1,1,100,100,0,?,?,?)
                """,receivable,tenant,company,partner,today.minusDays(10),today.minusDays(5),period);
        jdbc.update("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,open_amount,aging_date)
                VALUES(?,?,?,?,'receivable',?,'D',100,100,?)
                """,openItem,tenant,company,partner,receivable,today.minusDays(10));
    }


    private static final int HISTORY_PER_SOURCE = 240;
    private static final int ALERTS_PER_SOURCE = 1 + HISTORY_PER_SOURCE / 2;
    private static final List<AlertKind> POPULATED_RULES =
            List.of(AlertKind.BATCH_EXPIRED, AlertKind.RECEIVABLE_OVERDUE, AlertKind.PAYABLE_DUE);

    private record Sample(String label, CompanyAccess actor, long emptyCompany) {}

    @Test
    void checksScanThroughputAndRetainsScopeAcrossFourCompanies() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("erp_test_alert_scan");
        var first = prepare("A");
        var second = prepare("B");
        for (var sample : List.of(first, second)) {
            long tenantId = sample.actor().tenantId();
            long companyId = sample.actor().companyId();
            var scenarios = List.of(
                    new Sample(sample.label(), sample.actor(), sample.emptyCompany()),
                    new Sample(sample.label() + "-empty",
                            new CompanyAccess(tenantId, sample.actor().userId(), sample.emptyCompany(),
                                    true, Set.of(), sample.actor().permissions()), 0));
            for (var scenario : scenarios) scanAll(scenario, 0);
            for (var kind : POPULATED_RULES) {
                long eventId = jdbc.queryForObject("""
                        SELECT MIN(id) FROM alert_event WHERE tenant_id=? AND company_id=? AND rule_code=?
                        """, Long.class, tenantId, companyId, kind.code);
                var other = sample == first ? second.actor() : first.actor();
                assertThatThrownBy(() -> events.detail(other, eventId)).isInstanceOfSatisfying(
                        ApiException.class, failure -> assertThat(failure.getStatus()).isEqualTo(404));
                assertThatThrownBy(() -> events.detail(scenarios.getLast().actor(), eventId))
                        .isInstanceOfSatisfying(ApiException.class,
                                failure -> assertThat(failure.getStatus()).isEqualTo(404));
                events.handle(sample.actor(), eventId, "acknowledge", UUID.randomUUID().toString(),
                        new AlertEvents.Handle(0L, "容量样本预处理"));
            }
            for (int round = 1; round <= 3; round++) {
                for (var scenario : scenarios) scanAll(scenario, round);
                assertThat(count("alert_event", sample.actor())).isEqualTo(2L * ALERTS_PER_SOURCE + HISTORY_PER_SOURCE / 2);
                assertThat(count("notify_message", sample.actor())).isEqualTo(2L * ALERTS_PER_SOURCE + HISTORY_PER_SOURCE / 2);
                assertThat(count("alert_event", scenarios.getLast().actor())).isZero();
                assertThat(count("notify_message", scenarios.getLast().actor())).isZero();
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM alert_event WHERE tenant_id=? AND company_id=?
                          AND status='acknowledged' AND occurrence=1
                        """, Long.class, tenantId, companyId)).isEqualTo(POPULATED_RULES.size());
            }
        }
    }

    private Sample prepare(String label) {
        seed();
        var batchRows = new ArrayList<Object[]>();
        var stockRows = new ArrayList<Object[]>();
        var receivableRows = new ArrayList<Object[]>();
        var itemRows = new ArrayList<Object[]>();
        var payableRows = new ArrayList<Object[]>();
        var payableItems = new ArrayList<Object[]>();
        for (int index = 0; index < HISTORY_PER_SOURCE; index++) {
            long batchId = Ids.next();
            long receivableId = Ids.next();
            boolean overdue = index % 2 == 0;
            batchRows.add(new Object[] {batchId, tenant, company, sku, "CAP-" + index,
                    overdue ? today.minusDays(2) : today.plusDays(120), batchId});
            stockRows.add(new Object[] {Ids.next(), tenant, company, warehouse, sku, Long.toString(batchId)});
            receivableRows.add(new Object[] {receivableId, tenant, company, partner, receivableId,
                    today.minusDays(20), overdue ? today.minusDays(2) : today.plusDays(120), period});
            itemRows.add(new Object[] {Ids.next(), tenant, company, partner, receivableId, today.minusDays(20)});
            long payableId=Ids.next();
            payableRows.add(new Object[] {payableId,tenant,company,partner,payableId,today,
                    overdue?today.plusDays(5):today.plusDays(120),period});
            payableItems.add(new Object[] {Ids.next(),tenant,company,partner,payableId,today});
        }
        jdbc.batchUpdate("""
                INSERT INTO inv_batch(id,tenant_id,company_id,sku_id,batch_no,expiry_date,source_type,source_id)
                VALUES(?,?,?,?,?,?,'opening_entry',?)
                """, batchRows);
        jdbc.batchUpdate("""
                INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,lot_key,on_hand_qty)
                VALUES(?,?,?,?,?,?,5)
                """, stockRows);
        jdbc.batchUpdate("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,
                  untaxed_amount,tax_amount,business_date,due_date,period_id)
                VALUES(?,?,?,?,'charge','opening_entry',?,1,100,100,0,?,?,?)
                """, receivableRows);
        jdbc.batchUpdate("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,
                  open_amount,aging_date)
                VALUES(?,?,?,?,'receivable',?,'D',100,100,?)
                """, itemRows);
        jdbc.batchUpdate("""
                INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,
                  untaxed_amount,tax_amount,business_date,due_date,period_id)
                VALUES(?,?,?,?,'charge','opening_entry',?,1,100,100,0,?,?,?)
                """,payableRows);
        jdbc.batchUpdate("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,open_amount,aging_date)
                VALUES(?,?,?,?,'payable',?,'C',100,100,?)
                """,payableItems);
        return new Sample(label, actor, companyTwo);
    }

    private long count(String table, CompanyAccess scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND company_id=?",
                Long.class, scope.tenantId(), scope.companyId());
    }

    private void scanAll(Sample sample, int round) {
        var time = now.plus(round, ChronoUnit.DAYS);
        long tenantId = sample.actor().tenantId();
        long companyId = sample.actor().companyId();
        long scanStart = System.nanoTime();
        for (var rule : rules.list(tenantId, companyId)) {
            long start = System.nanoTime();
            scanner.scan(tenantId, companyId, rule.kind(), time, false);
            System.out.printf(Locale.ROOT, "ALERT_CAPACITY,%s,%d,%s,%.3f%n",
                    sample.label(), round, rule.kind().code, (System.nanoTime() - start) / 1e6);
        }
        System.out.printf(Locale.ROOT, "ALERT_CAPACITY,%s,%d,total,%.3f%n",
                sample.label(), round, (System.nanoTime() - scanStart) / 1e6);
    }
    private long createTenant() {
        long id=Ids.next();jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'预警测试')",id,"AL"+id);return id;
    }
    private long createCompany(long tenant,String code) {
        long id=Ids.next();jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'预警公司')",id,tenant,code);return id;
    }
}
