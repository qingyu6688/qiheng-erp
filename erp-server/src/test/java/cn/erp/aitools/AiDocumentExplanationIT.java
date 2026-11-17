package cn.erp.aitools;

import cn.erp.ErpApplication;
import cn.erp.fulfillment.ShipmentService;
import cn.erp.identity.CompanyAccess;
import cn.erp.period.PeriodService;
import cn.erp.shared.ApiException;
import cn.erp.shared.OrgScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class AiDocumentExplanationIT {
    private static final long TENANT=70101,COMPANY=70201,WAREHOUSE=70301,DEPARTMENT=75101;
    private static final long ORDER=71201,SHIPMENT=71501,PERIOD=70601;
    private static final Set<String> PERMISSIONS=Set.of("ai:use","sales_order:view","shipment:view","shipment:create",
            "shipment:submit","shipment:post","period:view","period:close","stock_misc_doc:view");
    private final CompanyAccess actor=new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),PERMISSIONS);

    @Autowired AiBusinessTools tools;
    @Autowired ShipmentService shipments;
    @Autowired PeriodService periods;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void ownership() {
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth) VALUES(?,?,?,'D','销售部','/75101/',1)",
                DEPARTMENT,TENANT,COMPANY);
        jdbc.update("UPDATE sales_order SET owner_department_id=? WHERE id=?",DEPARTMENT,ORDER);
        jdbc.update("UPDATE shipment SET owner_department_id=? WHERE id=?",DEPARTMENT,SHIPMENT);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(75104,?,70401,'customer')",TENANT);
    }

    @Test
    void orderStatesReuseSourceRulesAndExplainOnlyCurrentRemainingQuantity() {
        var draft=run(actor,"sales_order",ORDER);
        assertThat(draft).containsEntry("detailUrl","/sales-orders/71201");
        assertThat(summary(draft)).containsEntry("documentStatus","draft").containsEntry("checkPassed",false);
        assertThat(codes(draft)).containsExactly("STATE_CONFLICT");
        setOrderState("change_pending");
        assertThat(codes(run(actor,"sales_order",ORDER))).containsExactly("CHANGE_PENDING");
        setOrderState("approved");
        var ready=run(actor,"sales_order",ORDER);
        assertThat(codes(ready)).isEmpty();
        assertThat(summary(ready)).containsEntry("checkPassed",true);
        assertThat(summary(ready).get("allowedActions")).isEqualTo(List.of("create_shipment"));
        jdbc.update("UPDATE partner_role SET status='disabled' WHERE id=75104");
        assertThat(codes(run(actor,"sales_order",ORDER))).containsExactly("MASTER_DATA_DISABLED");
        jdbc.update("UPDATE partner_role SET status='active' WHERE id=75104");
        jdbc.update("UPDATE sales_order_line SET closed_base_qty=base_qty WHERE order_id=?",ORDER);
        assertThat(codes(run(actor,"sales_order",ORDER))).containsExactly("SHIP_QTY_EXCEEDED");
        assertThat(jdbc.queryForObject("SELECT status FROM shipment WHERE id=?",String.class,SHIPMENT)).isEqualTo("draft");
    }

    @Test
    void draftShipmentUsesLiveOrderQuantityAndClosedPeriod() {
        setOrderState("approved");
        var ready=run(actor,"shipment",SHIPMENT);
        assertThat(summary(ready)).containsEntry("checkPassed",true).containsEntry("allowedActions",List.of("submit"));
        assertThat(codes(ready)).isEmpty();
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",PERIOD);
        assertThat(codes(run(actor,"shipment",SHIPMENT))).containsExactly("PERIOD_CLOSED");
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",PERIOD);
        jdbc.update("UPDATE sales_order_line SET pending_ship_base_qty=1 WHERE id=71301");
        var blocked=run(actor,"shipment",SHIPMENT);
        assertThat(codes(blocked)).containsExactly("SHIP_QTY_EXCEEDED");
        assertThat(reasons(blocked).getFirst().get("links"))
                .isEqualTo(List.of(Map.of("label","SO-4","detailUrl","/sales-orders/71201")));
        var shipmentOnly=new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),Set.of("shipment:view","shipment:submit"));
        assertThat(reasons(run(shipmentOnly,"shipment",SHIPMENT)).getFirst()).doesNotContainKey("links");
        assertThat(jdbc.queryForObject("SELECT pending_ship_base_qty FROM sales_order_line WHERE id=71301",BigDecimal.class)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_shipment_pick WHERE tenant_id=? AND company_id=?",Long.class,TENANT,COMPANY)).isZero();
        assertThatThrownBy(()->shipments.submit(actor,SHIPMENT,UUID.randomUUID().toString(),0))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo("SHIP_QTY_EXCEEDED"));
    }

    @Test
    void submittedShipmentUsesCurrentStockWithoutMovingStock() {
        setOrderState("approved");
        shipments.submit(actor,SHIPMENT,UUID.randomUUID().toString(),0);
        assertThat(summary(run(actor,"shipment",SHIPMENT))).containsEntry("checkPassed",true);
        long previousMovements=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=?",Long.class,TENANT);
        jdbc.update("UPDATE inventory_cost SET qty=3 WHERE id=72001");
        assertThat(codes(run(actor,"shipment",SHIPMENT))).containsExactly("INVENTORY_INSUFFICIENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=?",Long.class,TENANT)).isEqualTo(previousMovements);
        assertThat(jdbc.queryForObject("SELECT status FROM shipment WHERE id=?",String.class,SHIPMENT)).isEqualTo("submitted");
    }

    @Test
    void trackedShipmentUsesTheSameBatchRequirementsAsSubmit() {
        setOrderState("approved");
        jdbc.update("UPDATE sku SET batch_managed=1,issue_strategy='manual' WHERE id=71001");
        var preview=run(actor,"shipment",SHIPMENT);
        assertThat(codes(preview)).containsExactly("VALIDATION_FAILED");
        assertThat(reasons(preview).getFirst()).containsEntry("message","请手工选择出库批次");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_shipment_pick WHERE tenant_id=?",Long.class,TENANT)).isZero();
        assertThatThrownBy(()->shipments.submit(actor,SHIPMENT,UUID.randomUUID().toString(),0))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getMessage()).isEqualTo("请手工选择出库批次"));
    }

    @Test
    void periodExplanationMatchesNativeFailuresAndFiltersLinksByPermission() {
        jdbc.update("UPDATE shipment SET status='submitted' WHERE id=?",SHIPMENT);
        jdbc.update("INSERT INTO income_expense_item(id,tenant_id,item_type,code,name) VALUES(75105,?,'expense','MISC','其他出库')",TENANT);
        jdbc.update("""
                INSERT INTO stock_misc_doc(id,tenant_id,company_id,document_no,direction,purpose,warehouse_id,income_expense_item_id,
                    business_date,owner_user_id,owner_department_id,status,created_by,updated_by)
                VALUES(75106,?,?,'MO-1','out','gift',?,75105,'2026-11-15',1,?,'pending',1,1)
                """,TENANT,COMPANY,WAREHOUSE,DEPARTMENT);
        jdbc.update("UPDATE cash_account_balance SET balance=200 WHERE cash_account_id=70501");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(75102,?,?,'202610','2026-10-01','2026-10-31')",
                TENANT,COMPANY);
        var nativeChecks=reasonsFromPeriod(periods.closeCheck(actor,PERIOD));
        var details=run(actor,"accounting_period",PERIOD);
        assertThat(details).containsEntry("detailUrl","/accounting-periods?id=70601");
        assertThat(codes(details)).containsAll(nativeChecks).contains("unposted_documents","inventory_reconciliation",
                "money_reconciliation","PERIOD_ORDER_INVALID");
        assertThat(reason(details,"unposted_documents").get("links"))
                .isEqualTo(List.of(Map.of("label","S1","detailUrl","/shipments/71501"),Map.of("label","MO-1","detailUrl","/stock-misc-out/75106")));
        assertThat(reason(details,"PERIOD_ORDER_INVALID").get("links"))
                .isEqualTo(List.of(Map.of("label","202610","detailUrl","/accounting-periods?id=75102")));
        var periodOnly=new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),Set.of("period:view","period:close"));
        assertThat(reason(run(periodOnly,"accounting_period",PERIOD),"unposted_documents")).doesNotContainKeys("links","documents","count");
        assertThat(jdbc.queryForObject("SELECT status FROM accounting_period WHERE id=?",String.class,PERIOD)).isEqualTo("open");
    }

    @Test
    void clearAndClosedPeriodsReturnDifferentReadinessWithoutChangingStatus() {
        long clear=75103, emptyCompany=70204;
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'D','空白验收公司')",emptyCompany,TENANT);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",
                clear,TENANT,emptyCompany);
        var alternate=new CompanyAccess(TENANT,1,emptyCompany,true,Set.of(),PERMISSIONS);
        var ready=run(alternate,"accounting_period",clear);
        assertThat(codes(ready)).isEmpty();
        assertThat(summary(ready)).containsEntry("documentStatus","open").containsEntry("checkPassed",true);
        assertThat(jdbc.queryForObject("SELECT status FROM accounting_period WHERE id=?",String.class,clear)).isEqualTo("open");
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",clear);
        var closed=run(alternate,"accounting_period",clear);
        assertThat(summary(closed)).containsEntry("documentStatus","closed").containsEntry("checkPassed",false);
        assertThat(codes(closed)).containsExactly("STATE_CONFLICT");
    }

    @Test
    void explanationAndRecheckIntersectPermissionsAndScope() {
        var before=run(actor,"sales_order",ORDER);
        assertThat(tools.documentScope(actor,input("sales_order",ORDER))).isEqualTo(before.get("scopeFingerprint"));
        setOrderState("change_pending");
        assertThat(tools.documentScope(actor,input("sales_order",ORDER))).isNotEqualTo(before.get("scopeFingerprint"));
        for(var restricted:List.of(
                new CompanyAccess(TENANT,1,70202,true,Set.of(),PERMISSIONS),
                new CompanyAccess(70102,1,70203,true,Set.of(),PERMISSIONS),
                new CompanyAccess(TENANT,1,COMPANY,false,Set.of(70302L),PERMISSIONS),
                new CompanyAccess(TENANT,1,COMPANY,false,Set.of(WAREHOUSE),PERMISSIONS,OrgScope.restricted(Set.of(99999L),Set.of(),99999L)))) {
            assertThatThrownBy(()->tools.documentScope(restricted,input("sales_order",ORDER)))
                    .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
            assertThatThrownBy(()->run(restricted,"shipment",SHIPMENT))
                    .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
        }
        for(var restricted:List.of(
                new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),Set.of("period:view")),
                new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),Set.of("period:close")),
                new CompanyAccess(TENANT,1,COMPANY,false,Set.of(WAREHOUSE),PERMISSIONS),
                new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),PERMISSIONS,OrgScope.restricted(Set.of(DEPARTMENT),Set.of(),DEPARTMENT)))) {
            assertThatThrownBy(()->run(restricted,"accounting_period",PERIOD))
                    .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(403));
        }
    }

    private void setOrderState(String status) {
        jdbc.update("UPDATE sales_order SET status=?,revision=1,approved_at=UTC_TIMESTAMP(3) WHERE id=?",status,ORDER);
    }

    private Map<String,Object> run(CompanyAccess scope,String type,long id) {
        return tools.query(scope,AiBusinessTools.Tool.DOCUMENT_EXPLAIN,input(type,id));
    }

    private AiBusinessInput input(String type,long id) {
        String body="{\"documentType\":\""+type+"\",\"documentId\":\""+id+"\"}";
        return AiBusinessInput.parse(body.getBytes(StandardCharsets.UTF_8),AiBusinessTools.Tool.DOCUMENT_EXPLAIN.fields);
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> summary(Map<String,Object> response) {
        return (Map<String,Object>)response.get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> reasons(Map<String,Object> response) {
        return (List<Map<String,Object>>)response.get("items");
    }

    private List<String> codes(Map<String,Object> response) {
        return reasons(response).stream().map(item->item.get("code").toString()).toList();
    }

    private Map<String,Object> reason(Map<String,Object> response,String code) {
        return reasons(response).stream().filter(item->code.equals(item.get("code"))).findFirst().orElseThrow();
    }

    private List<String> reasonsFromPeriod(Map<String,Object> response) {
        return reasons(response).stream().filter(item->!Boolean.TRUE.equals(item.get("passed")))
                .map(item->item.get("check").toString()).toList();
    }
}
