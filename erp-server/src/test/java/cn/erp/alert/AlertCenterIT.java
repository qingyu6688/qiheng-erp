package cn.erp.alert;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.notify.MessageService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class AlertCenterIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AlertRules rules;
    @Autowired AlertEvents events;
    @Autowired AlertScan scanner;
    @Autowired AlertProbes probes;
    @Autowired AlertSafetyStock safety;
    @Autowired MessageService messages;
    @Autowired cn.erp.fulfillment.ShipmentService shipments;

    private final Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final LocalDate today=LocalDate.ofInstant(now,AlertRules.ZONE);
    private long tenant,company,companyTwo,otherTenant,otherCompany,warehouse,sku,batch,user,partner,openItem,period;
    private CompanyAccess actor;

    @BeforeEach void seed() {
        tenant=createTenant();otherTenant=createTenant();company=createCompany(tenant,"A");companyTwo=createCompany(tenant,"B");
        otherCompany=createCompany(otherTenant,"C");warehouse=Ids.next();sku=Ids.next();batch=Ids.next();user=Ids.next();
        partner=Ids.next();period=Ids.next();openItem=Ids.next();
        long unit=Ids.next(),category=Ids.next(),product=Ids.next(),role=Ids.next();
        actor=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of("alert:view","alert:configure","alert:handle","batch:view","report:receivable_aging"));
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
        seedOverdueByRealSigning(unit,product);

    }

    private void seedOverdueByRealSigning(long unit,long product) {
        // 出库事实作为夹具，调用正式签收服务写入应收，禁止手工填到期日。
        var day=today.minusDays(10);long plainSku=Ids.next(),order=Ids.next(),line=Ids.next(),shipment=Ids.next(),shipmentLine=Ids.next();
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,partner);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'TERM','账期商品',0)",plainSku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,plainSku,unit);
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,status,pricing_version,term_type,term_value,total_amount,untaxed_amount,price_tax_inclusive,revision,approved_at,goods_amount) VALUES(?,?,?,'TERM-SO',?,?,'approved',2,'days',5,100,100,1,1,UTC_TIMESTAMP(3),100)",order,tenant,company,partner,day);
        jdbc.update("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_net,receivable_net,untaxed_amount,shipped_base_qty,tax_rate,tax_amount) VALUES(?,?,?,?,1,?,?,?,1,1,1,100,100,100,100,100,1,0,0)",line,tenant,company,order,plainSku,warehouse,unit);
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,status,period_id,posted_at,owner_user_id,delivery_method) VALUES(?,?,?,'TERM-SHIP',?,?,?,'posted',?,UTC_TIMESTAMP(3),?,'delivery')",shipment,tenant,company,partner,warehouse,day,period,user);
        jdbc.update("INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,unit_cost,cost_amount) VALUES(?,?,?,?,1,?,?,?,1,1,1,0,0)",shipmentLine,tenant,company,shipment,line,plainSku,unit);
        shipments.sign(actor,shipment,java.util.UUID.randomUUID().toString(),new cn.erp.fulfillment.ShipmentModels.Signed(0L,day,
                List.of(new cn.erp.fulfillment.ShipmentModels.SignedLine(shipmentLine,"1")),List.of()));
        openItem=jdbc.queryForObject("SELECT o.id FROM partner_open_item o JOIN receivable r ON r.id=o.source_id AND r.tenant_id=o.tenant_id WHERE o.tenant_id=? AND o.item_type='receivable' AND r.source_id=?",Long.class,tenant,shipment);
        assertThat(jdbc.queryForObject("SELECT due_date FROM receivable WHERE tenant_id=? AND source_id=?",java.sql.Date.class,tenant,shipment))
                .isEqualTo(java.sql.Date.valueOf(today.minusDays(5)));
    }

    @Test void payableDueWindowAndVisibilityRequirePayableReportPermission() {
        long expected=0;
        for(Integer days:java.util.Arrays.asList(3,8,null)) {
            long fact=Ids.next(),item=Ids.next();
            jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,due_date,period_id) VALUES(?,?,?,?,'charge','opening_entry',?,?,20,20,0,?,?,?)",
                    fact,tenant,company,partner,fact,fact,today,days==null?null:today.plusDays(days),period);
            jdbc.update("INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,open_amount,aging_date) VALUES(?,?,?,?,'payable',?,'C',20,20,?)",item,tenant,company,partner,fact,today);
            if(days!=null&&days==3) expected=item;
        }
        assertThat(probes.detect(tenant,company,rules.current(tenant,company,AlertKind.PAYABLE_DUE),now)).extracting(AlertCandidate::id).containsExactly(expected);
        scanner.scan(tenant,company,AlertKind.PAYABLE_DUE,now,false);
        assertThat(events.list(actor,"payable_due",null,"open",null,30).get("items")).asList().isEmpty();
        var payerPermissions=new java.util.HashSet<>(actor.permissions());payerPermissions.add("report:payable_aging");
        var payer=new CompanyAccess(tenant,user,company,true,Set.of(),payerPermissions);
        assertThat(events.list(payer,"payable_due",null,"open",null,30).get("items")).asList().hasSize(1);
    }

    @Test void scansAllPresetQueriesAndRetainsOnlyOneEventPerOverdueItemAndBatch() {
        for (var rule : rules.list(tenant,company)) scanner.scan(tenant,company,rule.kind(),now,false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_rule WHERE tenant_id=? AND company_id=? AND next_run_at IS NOT NULL",
                Long.class,tenant,company)).isEqualTo(AlertKind.values().length);
        assertThat(events.unhandled(actor)).isEqualTo(2);
        assertThat(events.list(actor,null,null,"open",null,30).get("items")).asList().hasSize(2);
        var rows=jdbc.queryForList("SELECT id,rule_code,status,occurrence FROM alert_event WHERE tenant_id=? AND company_id=? ORDER BY id",tenant,company);
        assertThat(rows.stream().map(row->row.get("rule_code")).toList()).containsExactlyInAnyOrder("batch_expired","receivable_overdue");
        for (var rule : rules.list(tenant,company)) scanner.scan(tenant,company,rule.kind(),now.plus(1,ChronoUnit.DAYS),false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE tenant_id=? AND company_id=?",Long.class,tenant,company)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE tenant_id=? AND company_id=? AND source_type='alert_event' AND user_id=?",Long.class,tenant,company,user)).isEqualTo(2);
        assertThat(messages.list(actor,"alert",null,null,30).get("items")).asList().hasSize(2);

        var onlyAging=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of("alert:view","report:receivable_aging"));
        assertThat(events.unhandled(onlyAging)).isEqualTo(1);
        assertThat(messages.list(onlyAging,"alert",null,null,30).get("items")).asList().hasSize(1);
        var limited=new CompanyAccess(tenant,user,company,false,Set.of(Ids.next()),actor.permissions());
        assertThat(events.unhandled(limited)).isZero();
        assertThat(messages.list(limited,"alert",null,null,30).get("items")).asList().isEmpty();
        var restrictedDept=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of("alert:view","report:receivable_aging"),
                OrgScope.restricted(Set.of(Ids.next()),Set.of(),0));
        assertThat(events.unhandled(restrictedDept)).isZero();
        for (var foreign : List.of(new CompanyAccess(otherTenant,user,otherCompany,true,Set.of(),actor.permissions()),
                new CompanyAccess(tenant,user,companyTwo,true,Set.of(),actor.permissions()))) {
            assertThat(events.unhandled(foreign)).isZero();
            long id=((Number)rows.getFirst().get("id")).longValue();
            assertThatThrownBy(()->events.detail(foreign,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        }
    }

    @Test void repeatedItemFailureProducesOneCompanyScopedVisibleAlertAndResolvesOnRecovery() {
        long id = Ids.next();
        jdbc.update("""
                INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
                SELECT ?,r.tenant_id,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
                WHERE r.tenant_id=? AND r.code='SYSTEM_ADMIN' AND p.code='job:view'
                """, Ids.next(), tenant);
        jdbc.update("""
                INSERT INTO job_definition(tenant_id,code,name,schedule,last_result)
                VALUES(?,'crm.pool_reclaim','公海回收','interval:1000','partial_failed')
                """, tenant);
        jdbc.update("INSERT INTO crm_pool_rule(tenant_id,company_id,enabled,apply_to_levels) VALUES(?,?,1,JSON_ARRAY())", tenant, company);
        jdbc.update("""
                INSERT INTO job_item_failure(id,tenant_id,company_id,job_code,item_key,target_id,failure_count,error_code,last_failed_at)
                VALUES(?,?,?,'crm.pool_reclaim',?,?,2,'COMPANY_FAULT',UTC_TIMESTAMP(3))
                """, id, tenant, company, Long.toString(company), company);
        scanner.scan(tenant, company, AlertKind.JOB_FAILED, now, false);
        var admin = new CompanyAccess(tenant, user, company, true, Set.of(), Set.of("alert:view", "job:view"));
        assertThat(events.unhandled(admin)).isZero();

        jdbc.update("UPDATE job_item_failure SET failure_count=3 WHERE id=?", id);
        scanner.scan(tenant, company, AlertKind.JOB_FAILED, now.plus(1, ChronoUnit.HOURS), false);
        scanner.scan(tenant, company, AlertKind.JOB_FAILED, now.plus(2, ChronoUnit.HOURS), false);
        assertThat(events.unhandled(admin)).isEqualTo(1);
        assertThat(events.unhandled(actor)).isZero();
        assertThat(events.unhandled(new CompanyAccess(tenant, user, companyTwo, true, Set.of(), admin.permissions()))).isZero();
        assertThat(events.unhandled(new CompanyAccess(otherTenant, user, otherCompany, true, Set.of(), admin.permissions()))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notify_message WHERE tenant_id=? AND company_id=? AND source_type='alert_event' AND user_id=?
                """, Long.class, tenant, company, user)).isEqualTo(1);

        jdbc.update("DELETE FROM job_item_failure WHERE id=?", id);
        scanner.scan(tenant, company, AlertKind.JOB_FAILED, now.plus(3, ChronoUnit.HOURS), false);
        assertThat(events.unhandled(admin)).isZero();
    }

    @Test void emptyRealTimeSignalDoesNotWriteSetupAndScheduledScanPersistsNextRunWithoutAlert() {
        scanner.scan(tenant,company,AlertKind.NEGATIVE_MARGIN,now,true);
        assertThat(rules.current(tenant,company,AlertKind.NEGATIVE_MARGIN).id()).isZero();
        scanner.scan(tenant,company,AlertKind.BATCH_NEAR_EXPIRY,now,false);
        var daily=rules.current(tenant,company,AlertKind.BATCH_NEAR_EXPIRY);
        assertThat(daily.id()).isPositive();
        assertThat(daily.nextRun()).isEqualTo(AlertRules.nextRun("daily",now));
        assertThat(daily.nextRun().atZone(AlertRules.ZONE).getHour()).isEqualTo(8);
        assertThat(events.unhandled(actor)).isZero();
    }

    @Test void processesAcknowledgementAndAutomaticallyReopensClearedConditionWithSameId() {
        scanner.scan(tenant,company,AlertKind.BATCH_EXPIRED,now,false);
        long id=jdbc.queryForObject("SELECT id FROM alert_event WHERE tenant_id=? AND company_id=?",Long.class,tenant,company);
        var input=new AlertEvents.Handle(0L,"现场核验");
        assertThat(events.handle(actor,id,"acknowledge","ack-test",input)).containsEntry("status","acknowledged");
        assertThat(events.handle(actor,id,"acknowledge","ack-test",input)).containsEntry("status","acknowledged");
        assertThat(events.unhandled(actor)).isZero();
        scanner.scan(tenant,company,AlertKind.BATCH_EXPIRED,now.plus(1,ChronoUnit.HOURS),false);
        assertThat(events.detail(actor,id)).containsEntry("status","acknowledged");
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=? AND company_id=? AND lot_key=?",tenant,company,Long.toString(batch));
        scanner.scan(tenant,company,AlertKind.BATCH_EXPIRED,now.plus(2,ChronoUnit.HOURS),false);
        assertThat(events.detail(actor,id)).containsEntry("status","resolved");
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=5 WHERE tenant_id=? AND company_id=? AND lot_key=?",tenant,company,Long.toString(batch));
        scanner.scan(tenant,company,AlertKind.BATCH_EXPIRED,now.plus(3,ChronoUnit.HOURS),false);
        var reopened=events.detail(actor,id);
        assertThat(reopened).containsEntry("status","open");
        assertThat(((Number)reopened.get("occurrence")).intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM alert_event WHERE tenant_id=? AND company_id=?",Long.class,tenant,company)).isEqualTo(1);
        assertThat(messages.list(actor,"alert",null,null,30).get("items")).asList().hasSize(1);
    }

    @Test void appliesCompanySafetyThresholdForSkuWithoutInventoryAndProtectsRuleWrites() {
        var other=new CompanyAccess(tenant,user,companyTwo,true,Set.of(),actor.permissions());
        assertThat(safety.update(actor,sku,new AlertSafetyStock.Input(0L,"7.000001"))).containsEntry("quantity","7.000001");
        assertThat(safety.list(other,"A",null,30).get("items")).asList().hasSize(1).first().satisfies(item->
                assertThat(((Map<?,?>)item).get("quantity")).isEqualTo("0.000000"));
        scanner.scan(tenant,company,AlertKind.STOCK_BELOW_SAFETY,now,false);
        var row=jdbc.queryForMap("SELECT detail,warehouse_id FROM alert_event WHERE tenant_id=? AND company_id=?",tenant,company);
        assertThat(((Number)row.get("warehouse_id")).longValue()).isEqualTo(warehouse);
        assertThat(row.get("detail").toString()).contains("7.000001").contains("可用 0");
        var limited=new CompanyAccess(tenant,user,company,false,Set.of(warehouse),actor.permissions());
        assertThatThrownBy(()->rules.list(limited)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
    }

    private long createTenant() {
        long id=Ids.next();jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'预警测试')",id,"AL"+id);return id;
    }
    private long createCompany(long tenant,String code) {
        long id=Ids.next();jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'预警公司')",id,tenant,code);return id;
    }
}
