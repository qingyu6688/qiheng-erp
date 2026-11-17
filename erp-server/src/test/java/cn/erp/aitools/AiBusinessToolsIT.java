package cn.erp.aitools;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.salesorder.SalesInventoryQueries;
import cn.erp.shared.ApiException;
import cn.erp.shared.OrgScope;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.Set;
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
class AiBusinessToolsIT {
    private static final long TENANT=70101, COMPANY=70201, WAREHOUSE=70301, DEPARTMENT=75101;
    private static final LocalDate DAY=LocalDate.of(2026,11,15);
    private static final Set<String> PERMISSIONS=Set.of("ai:use","inventory:view","sales_order:view","purchase_order:view","invoice_registration:view",
            "report:receivable_aging","report:payable_aging","report:cash_journal","report:sales_summary","report:gross_margin","cost:view","partner:view","sku:view");
    private final CompanyAccess actor=new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),PERMISSIONS);
    @Autowired AiBusinessTools tools;
    @Autowired ReportService reports;
    @Autowired SalesInventoryQueries inventory;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void facts() {
        jdbc.update("""
                INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES
                (75201,70101,70401,'customer'),(75202,70101,70401,'supplier'),(75203,70101,70402,'customer')
                """);
        jdbc.update("INSERT INTO sku_barcode(id,tenant_id,sku_id,unit_id,barcode) VALUES(75204,70101,71001,70701,'AI-CODE')");
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth) VALUES(75101,70101,70201,'E1','销售部','/75101/',1)");
        jdbc.update("UPDATE sales_order SET owner_department_id=75101 WHERE id=71201");
        jdbc.update("UPDATE purchase_order_line SET warehouse_id=70301 WHERE id=71801");
        jdbc.update("UPDATE shipment SET owner_department_id=75101,status='signed',posted_at='2026-11-15 10:00',period_id=70601,sign_date='2026-11-15',sign_period_id=70601,signed_at='2026-11-15 11:00',receivable_amount=100,cost_amount=30,signed_cost_amount=30,untaxed_amount=90,tax_amount=10 WHERE id=71501");
        jdbc.update("UPDATE shipment_line SET unit_cost=7.5,cost_amount=30,signed_base_qty=4,signed_cost_amount=30,receivable_amount=100,untaxed_amount=90,tax_amount=10 WHERE id=71601");
        jdbc.update("INSERT INTO journal_line(id,tenant_id,company_id,journal_id,line_no,account_code,direction,amount,cash_account_id) VALUES(75205,70101,70201,73001,1,'FUNDS','D',100,70501)");
        jdbc.update("UPDATE partner_open_item SET open_amount=80,created_at='2026-11-15 05:00:00' WHERE id=72701");
        jdbc.update("UPDATE settlement_allocation SET amount=20,created_at='2026-11-16 05:00:00' WHERE id=72801");
        jdbc.update("""
                INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES(75206,70101,70201,70401,'charge','opening_entry',75208,75209,70,70,0,'2026-11-15',70601)
                """);
        jdbc.update("INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,amount,open_amount,aging_date,created_at) VALUES(75207,70101,70201,70401,'payable',75206,'C',70,70,'2026-11-15','2026-11-15 05:00:00')");
    }

    @Test
    void toolAmountsMatchReportsAndUsesHistoricalCutoffAndClosingWhenNoDailyMovement() {
        var today=run(actor,"receivable_balance","{\"customer\":\"A\"}");
        assertMatches(today,ReportType.RECEIVABLE_AGING,new ReportQuery(null,null,null,null,70401L,null,false),"openAmount","80.00");
        var historical=run(actor,"receivable_balance","{\"customer\":\"A\",\"date\":\"2026-11-15\"}");
        assertMatches(historical,ReportType.RECEIVABLE_AGING,new ReportQuery(null,null,null,null,70401L,null,false,false,null,null,DAY),"openAmount","100.00");
        assertThat(historical.get("detailUrl").toString()).contains("agingDate=2026-11-15");
        assertMatches(run(actor,"payable_balance","{\"supplier\":\"甲客户\"}"),ReportType.PAYABLE_AGING,
                new ReportQuery(null,null,null,null,70401L,null,false),"openAmount","70.00");
        var nextDay=DAY.plusDays(1);
        assertMatches(run(actor,"cash_balance","{\"account\":\"C1\",\"date\":\"2026-11-16\"}"),ReportType.CASH_JOURNAL,
                new ReportQuery(nextDay,nextDay,null,null,null,70501L,false),"closingBalance","100.00");
        for(String dimension:List.of("customer","sku","department","business_user")){
            var query=new ReportQuery(DAY,DAY,null,null,null,null,false,false,null,dimension);
            String body="{\"dateFrom\":\"2026-11-15\",\"dateTo\":\"2026-11-15\",\"groupBy\":\""+dimension+"\"}";
            assertMatches(run(actor,"sales_summary",body),ReportType.SALES_SUMMARY,query,"amount","100.00");
            assertMatches(run(actor,"gross_margin",body),ReportType.GROSS_MARGIN,query,"grossMargin","60.00");
        }
    }

    @Test
    void warehouseAndBarcodeAndOrderAndDocumentsReuseScope() {
        var stock=run(actor,"available_stock","{\"sku\":\"AI-CODE\",\"warehouse\":\"W1\"}");
        var page=inventory.balances(actor,"70301","",null,20,false,false,71001L);
        assertThat(row(stock).get("availableQty")).isEqualTo(row(page).get("availableQty")).isEqualTo("5.000000");
        assertThat(stock.get("detailUrl").toString()).isEqualTo("/inventory-balances?skuId=71001&warehouseId=70301");
        var hiddenWarehouse=new CompanyAccess(TENANT,1,COMPANY,false,Set.of(99999L),PERMISSIONS);
        denied(404,()->run(hiddenWarehouse,"available_stock","{\"sku\":\"AI-CODE\",\"warehouse\":\"W1\"}"));
        denied(403,()->run(hiddenWarehouse,"receivable_balance","{\"customer\":\"A\"}"));
        denied(404,()->run(actor,"available_stock","{\"sku\":\"AI-CODE\",\"warehouse\":\"W2\"}"));
        denied(404,()->run(new CompanyAccess(70102,1,70203,true,Set.of(),PERMISSIONS),"available_stock","{\"sku\":\"AI-CODE\"}"));
        assertThat(row(run(actor,"order_progress","{\"orderNo\":\"SO-4\"}"))).containsEntry("detailUrl","/sales-orders/71201");
        assertThat(row(run(actor,"purchase_progress","{\"orderNo\":\"PO1\"}"))).containsEntry("detailUrl","/purchase-orders/71701");
        assertThat(row(run(actor,"document_search","{\"type\":\"sales-orders\",\"keyword\":\"SO-4\",\"status\":\"draft\"}"))).containsEntry("id","71201");
        denied(404,()->run(new CompanyAccess(TENANT,1,70202,true,Set.of(),PERMISSIONS),"order_progress","{\"orderNo\":\"SO-4\"}"));
        var sameDepartment=new CompanyAccess(TENANT,1,COMPANY,false,Set.of(WAREHOUSE),PERMISSIONS,OrgScope.restricted(Set.of(DEPARTMENT),Set.of(),DEPARTMENT));
        var otherDepartment=new CompanyAccess(TENANT,1,COMPANY,false,Set.of(WAREHOUSE),PERMISSIONS,OrgScope.restricted(Set.of(99999L),Set.of(),99999L));
        assertThat(row(run(sameDepartment,"order_progress","{\"orderNo\":\"SO-4\"}"))).containsEntry("id","71201");
        denied(404,()->run(otherDepartment,"order_progress","{\"orderNo\":\"SO-4\"}"));
        assertThat(run(otherDepartment,"document_search","{\"type\":\"sales-orders\",\"keyword\":\"SO-4\"}")).containsEntry("items",List.of());
    }

    @Test
    void candidatesStrictInputAndCostProtectSensitiveBusinessFields() {
        var ambiguous=run(actor,"available_stock","{\"sku\":\"商品\"}");
        assertThat(ambiguous).containsEntry("status","ambiguous").containsEntry("items",List.of());
        assertThat(ambiguous.get("candidates")).isEqualTo(List.of(Map.of("code","SKU-A","name","商品 A"),
                Map.of("code","SKU-B","name","商品 B"),Map.of("code","SKU-C","name","商品 C")));
        var noCost=new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),Set.of("inventory:view","sku:view","report:gross_margin"));
        assertThat(row(run(noCost,"available_stock","{\"sku\":\"SKU-A\"}"))).doesNotContainKeys("costAmount","unitCost");
        denied(403,()->run(noCost,"gross_margin","{\"dateFrom\":\"2026-11-15\",\"dateTo\":\"2026-11-15\"}"));
        denied(400,()->run(actor,"available_stock","{\"sku\":\"SKU-A\",\"companyId\":\"70202\"}"));
        denied(400,()->run(actor,"available_stock","{\"sku\":\"SKU-A\",\"sku\":\"SKU-B\"}"));
        denied(400,()->run(actor,"available_stock","{\"sku\":\"SKU-A\"} {}"));
        denied(400,()->run(actor,"order_progress","{}"));
        denied(400,()->run(actor,"payable_balance","{\"supplier\":\"A\",\"date\":\"2026-02-30\"}"));
        assertThat(run(actor,"available_stock","{\"sku\":\"SKU-A\"}").get("scopeFingerprint"))
                .isNotEqualTo(run(noCost,"available_stock","{\"sku\":\"SKU-A\"}").get("scopeFingerprint"));
    }

    private void assertMatches(Map<String,Object> tool,ReportType type,ReportQuery query,String field,String expected) {
        Object actual=((Map<?,?>)tool.get("summary")).get(field);
        assertThat(actual).isEqualTo(reports.snapshot(actor,type,query).summary().get(field)).isEqualTo(expected);
    }

    private Map<String,Object> run(CompanyAccess actor,String code,String body) {
        var tool=AiBusinessTools.Tool.parse(code);
        return tools.query(actor,tool,AiBusinessInput.parse(body.getBytes(StandardCharsets.UTF_8),tool.fields));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> row(Map<String,Object> result) {
        return ((List<Map<String,Object>>)result.get("items")).getFirst();
    }

    private void denied(int status,org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(status));
    }
}
