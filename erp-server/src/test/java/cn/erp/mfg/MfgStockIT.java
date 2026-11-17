package cn.erp.mfg;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.identity.AccessService;
import cn.erp.inventory.InventoryModels.*;
import cn.erp.inventory.InventoryService;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import cn.erp.workflow.SimpleModel;
import cn.erp.workflow.WorkflowModels;
import cn.erp.workflow.WorkflowTasks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.mfg.MfgStockModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class MfgStockIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired MfgStockDocuments documents;
    @Autowired MfgIssueWorkflow workflow;
    @Autowired AccessService access;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired InventoryService inventory;
    @Autowired ReversalService reversals;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private long tenant, company, issueWarehouse, receiveWarehouse, department, raw, finished, unit, order, material, period, bin;
    private CompanyAccess maker, reviewer;

    @BeforeTransaction void seedCompany() {
        tenant = Ids.next();
        company = Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'生产过账验收')", tenant, "MG" + tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'M','生产验收公司')", company, tenant);
    }

    @AfterTransaction void cleanCompany() {
        jdbc.update("DELETE FROM document_sequence WHERE tenant_id=? AND company_id=?", tenant, company);
        jdbc.update("DELETE FROM company WHERE tenant_id=? AND id=?", tenant, company);
        jdbc.update("DELETE FROM tenant WHERE id=?", tenant);
    }

    @BeforeEach void seed() {
        issueWarehouse = Ids.next();
        receiveWarehouse = Ids.next();
        department = Ids.next();
        raw = Ids.next();
        finished = Ids.next();
        unit = Ids.next();
        period = Ids.next();
        order = Ids.next();
        material = Ids.next();
        bin = Ids.next();
        var permissions = Set.of("mfg_issue:view", "mfg_issue:create", "mfg_issue:submit", "mfg_issue:approve", "mfg_issue:post",
                "mfg_receipt:view", "mfg_receipt:create", "mfg_receipt:submit", "mfg_receipt:approve", "mfg_receipt:post", "cost:view",
                "reversal:view", "reversal:create", "reversal:approve", "reversal:post");
        maker = new CompanyAccess(tenant, Ids.next(), company, true, Set.of(), permissions, new OrgScope(List.of(), department));
        reviewer = new CompanyAccess(tenant, Ids.next(), company, true, Set.of(), permissions);
        var month = YearMonth.from(today);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                period, tenant, company, month.toString().replace("-", ""), month.atDay(1), month.atEndOfMonth());
        seedWarehouses();
        seedCatalog();
        seedWorkOrder();
        receiveRaw("ORIGINAL", "100", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10");
    }

    private void seedWarehouses() {
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'RAW','原料仓'),(?,?,?,'FG','完工仓')",
                issueWarehouse, tenant, company, receiveWarehouse, tenant, company);
        long zone = Ids.next();
        jdbc.update("INSERT INTO wms_zone(id,tenant_id,company_id,warehouse_id,code,name,type) VALUES(?,?,?,?,'Z','原料存储','storage')",
                zone, tenant, company, issueWarehouse);
        jdbc.update("INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name,mixed_batch_allowed) VALUES(?,?,?,?,?,'A-01','原料一位',1)",
                bin, tenant, company, issueWarehouse, zone);
        jdbc.update("UPDATE warehouse SET location_managed=1 WHERE tenant_id=? AND company_id=? AND id=?", tenant, company, issueWarehouse);
    }

    private void seedCatalog() {
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')", unit, tenant);
        long category = Ids.next(), rawProduct = Ids.next(), finishedProduct = Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'MC','生产分类','/')", category, tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'RAW','原材料',?),(?,?,?,'FIN','成品',?)",
                rawProduct, tenant, category, unit, finishedProduct, tenant, category, unit);
        jdbc.update("""
                INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate,batch_managed,serial_managed,issue_strategy)
                VALUES(?,?,?,'RAW','原材料',0,1,1,'fifo'),(?,?,?,'FIN','成品',0,0,0,'fifo')
                """, raw, tenant, rawProduct, finished, tenant, finishedProduct);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1),(?,?,?,?,1,1)",
                Ids.next(), tenant, raw, unit, Ids.next(), tenant, finished, unit);
    }

    private void seedWorkOrder() {
        long bom = Ids.next(), bomLine = Ids.next();
        jdbc.update("""
                INSERT INTO mfg_bom(id,tenant_id,company_id,sku_id,version_no,unit_id,base_qty,base_factor,valid_from,status,owner_user_id,owner_department_id)
                VALUES(?,?,?,?,'V1',?,1,1,?,'approved',?,?)
                """, bom, tenant, company, finished, unit, today, maker.userId(), department);
        jdbc.update("""
                INSERT INTO mfg_bom_line(id,tenant_id,company_id,bom_id,line_no,component_sku_id,unit_id,qty,base_factor)
                VALUES(?,?,?,?,1,?,?,1,1)
                """, bomLine, tenant, company, bom, raw, unit);
        jdbc.update("""
                INSERT INTO mfg_work_order(id,tenant_id,company_id,document_no,sku_id,bom_id,unit_id,plan_qty,
                    issue_warehouse_id,receive_warehouse_id,plan_start_date,plan_finish_date,status,over_receive_pct,
                    owner_department_id,owner_user_id,created_by,updated_by)
                VALUES(?,?,?,'MO-1',?,?,?,3,?,?,?,?,'released',10,?,?,?,?)
                """, order, tenant, company, finished, bom, unit, issueWarehouse, receiveWarehouse, today, today,
                department, maker.userId(), maker.userId(), maker.userId());
        jdbc.update("""
                INSERT INTO mfg_work_order_material(id,tenant_id,company_id,work_order_id,line_no,bom_line_id,
                    component_sku_id,unit_id,issue_warehouse_id,required_qty,path_json)
                VALUES(?,?,?,?,1,?,?,?,?,3,JSON_ARRAY())
                """, material, tenant, company, order, bomLine, raw, unit, issueWarehouse);
    }

    @Test void overIssueRequiresAnotherReviewerAndReturnKeepsOriginalCostAndSerial() {
        var issue = issueDraft("issue", "4", null, "R1", "R2", "R3", "R4");
        long issueId = number(issue, "id");
        assertThat(workflow.snapshot(maker, issueId)).containsEntry("overIssue", true);
        assertThat(command(maker, Type.ISSUE, issueId, "submit", 0)).containsEntry("overIssue", true);
        reject("SAME_OPERATOR_NOT_ALLOWED", () -> command(maker, Type.ISSUE, issueId, "approve", 1));
        reject("STATE_CONFLICT", () -> command(maker, Type.ISSUE, issueId, "post", 1));
        command(reviewer, Type.ISSUE, issueId, "approve", 1);
        var token = key();
        var issued = documents.command(maker, Type.ISSUE, issueId, "post", token, new Revision(2L, null, null));
        documents.command(maker, Type.ISSUE, issueId, "post", token, new Revision(2L, null, null));
        var issueLine = firstLine(issued);
        assertThat(issueLine).containsEntry("amount", "40.00");
        long origin = number(issueLine, "id");
        receiveRaw("NEW", "100", "N1", "N2");
        long returned = number(issueDraft("return", "2", origin, "R1", "R2"), "id");
        var returnedLine = firstLine(post(Type.ISSUE, returned));
        assertThat(returnedLine).containsEntry("amount", "20.00");
        assertThat(serialStatus("R1")).isEqualTo("returned");
        assertThat(rawBalance().cost()).isEqualByComparingTo("180");
        assertThat(materialQuantity("returned_qty")).isEqualByComparingTo("2");
        reject("DEPENDENCY_EXISTS", () -> createReverse(Type.ISSUE, issueId));
        reverse(Type.ISSUE, returned);
        reverse(Type.ISSUE, issueId);
        assertThat(rawBalance().cost()).isEqualByComparingTo("200");
        assertThat(serialStatus("R1")).isEqualTo("in_stock");
        assertThat(materialQuantity("issued_qty")).isZero();
        assertThat(materialQuantity("returned_qty")).isZero();
        var wip = jdbc.queryForObject("SELECT COALESCE(SUM(IF(direction='D',amount,-amount)),0) FROM journal_line WHERE tenant_id=? AND company_id=? AND account_code='WIP'",
                BigDecimal.class, tenant, company);
        assertThat(wip).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM mfg_work_order WHERE id=?", String.class, order)).isEqualTo("released");
    }

    @Test void remainingDemandIsCheckedAgainAfterPartialIssueWasApproved() {
        long first = number(issueDraft("issue", "2", null, "R1", "R2"), "id");
        long second = number(issueDraft("issue", "2", null, "R3", "R4"), "id");
        assertThat(command(maker, Type.ISSUE, first, "submit", 0)).containsEntry("overIssue", false);
        assertThat(command(maker, Type.ISSUE, second, "submit", 0)).containsEntry("overIssue", false);
        command(reviewer, Type.ISSUE, first, "approve", 1);
        command(reviewer, Type.ISSUE, second, "approve", 1);
        command(maker, Type.ISSUE, first, "post", 2);
        reject("MFG_OVER_ISSUE_REVIEW", () -> command(maker, Type.ISSUE, second, "post", 2));
        assertThat(materialQuantity("issued_qty")).isEqualByComparingTo("2");
        assertThat(rawBalance().onHand()).isEqualByComparingTo("8");
        assertThat(serialStatus("R3")).isEqualTo("in_stock");
    }

    @Test void configuredOverIssueUsesTheFlowableApprovalTask() {
        long role = Ids.next();
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'MGFLOW','生产流程验收角色')", role, tenant);
        var permissions = jdbc.queryForList("""
                SELECT id FROM sys_permission WHERE code IN ('mfg_issue:view','mfg_issue:submit','mfg_issue:approve','mfg_issue:post',
                    'workflow_model:create','workflow_model:publish','workflow_binding:update')
                """, Long.class).stream()
                .map(permission -> new Object[]{Ids.next(), tenant, role, permission}).toList();
        jdbc.batchUpdate("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)", permissions);
        for (var user : List.of(maker, reviewer)) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,'生产流程验收员','非登录验收摘要')",
                    user.userId(), tenant, "U" + user.userId());
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)", Ids.next(), tenant, user.userId(), role);
            jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)", Ids.next(), tenant, user.userId(), company);
            jdbc.update("INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(?,?,?)", tenant, company, user.userId());
        }
        var admin = access.forUser(tenant, maker.userId(), company);
        var approvedBy = access.forUser(tenant, reviewer.userId(), company);
        var reviewNode = new SimpleModel.Node("stockReview", "approver", "超领复核",
                new SimpleModel.Assignee("users", List.of(reviewer.userId()), null, null, null, null, null, "admin"),
                "and", null, null, null, false);
        var start = new SimpleModel.Node("root", "start", "发起人", null, null, null, null, reviewNode, false);
        var model = number(models.save(admin, null, key(), new WorkflowModels.Input(company, "OverIssue", "超领验收", null, Type.ISSUE.head, start, 0L)), "id");
        models.change(admin, model, "publish", 0, key());
        models.bind(admin, null, key(), new WorkflowModels.Binding(company, Type.ISSUE.head, model, 0,
                new SimpleModel.Condition("overIssue", "==", "true", null, null), true, 0L));
        long issue = number(issueDraft("issue", "4", null, "R1", "R2", "R3", "R4"), "id");
        var submitted = command(admin, Type.ISSUE, issue, "submit", 0);
        long instance = number(submitted, "workflowInstanceId");
        reject("WORKFLOW_IN_PROGRESS", () -> command(approvedBy, Type.ISSUE, issue, "approve", 1));
        long task = jdbc.queryForObject("SELECT id FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND assignee_id=?",
                Long.class, tenant, instance, reviewer.userId());
        tasks.decide(approvedBy, task, "approve", key(), new WorkflowTasks.Decision(0L, "同意超领", null, null, null, null, null));
        assertThat(documents.detail(admin, Type.ISSUE, issue)).containsEntry("status", "approved");
        assertThat(command(admin, Type.ISSUE, issue, "post", 2)).containsEntry("status", "posted");
    }

    @Test void rejectsExcessFinishAndProtectsBothWarehousesAndCurrentDepartment() {
        long issue = number(issueDraft("issue", "3", null, "R1", "R2", "R3"), "id");
        post(Type.ISSUE, issue);
        var outOfDepartment = new CompanyAccess(tenant, reviewer.userId(), company, true, Set.of(), reviewer.permissions(),
                OrgScope.restricted(Set.of(Ids.next()), Set.of(), 0));
        var partialWarehouse = new CompanyAccess(tenant, reviewer.userId(), company, false, Set.of(issueWarehouse), reviewer.permissions());
        var readOnly = new CompanyAccess(tenant, reviewer.userId(), company, true, Set.of(), Set.of("mfg_issue:view"));
        var otherCompany = new CompanyAccess(tenant, reviewer.userId(), Ids.next(), true, Set.of(), reviewer.permissions());
        var otherTenant = new CompanyAccess(Ids.next(), reviewer.userId(), company, true, Set.of(), reviewer.permissions());
        for (var outside : List.of(outOfDepartment, partialWarehouse, otherCompany, otherTenant)) {
            reject("NOT_FOUND", () -> documents.detail(outside, Type.ISSUE, issue));
            assertThat(documents.list(outside, Type.ISSUE, new Filter(null, null, null, null, null, 10)).get("items")).isEqualTo(List.of());
        }
        assertThat(firstLine(documents.detail(readOnly, Type.ISSUE, issue))).doesNotContainKeys("amount", "unitCost");
        long tooMuch = receiptDraft("3.4");
        command(maker, Type.RECEIPT, tooMuch, "submit", 0);
        command(reviewer, Type.RECEIPT, tooMuch, "approve", 1);
        reject("MFG_OVER_RECEIPT", () -> command(maker, Type.RECEIPT, tooMuch, "post", 2));
        assertThat(inventory.balance(maker, new Key(receiveWarehouse, finished)).onHand()).isZero();
        var first = firstLine(post(Type.RECEIPT, receiptDraft("2")));
        assertThat(first).containsEntry("amount", "20.00");
        var secondId = receiptDraft("1.3");
        post(Type.RECEIPT, secondId);
        assertThat(inventory.balance(maker, new Key(receiveWarehouse, finished)).onHand()).isEqualByComparingTo("3.3");
        reverse(Type.RECEIPT, secondId);
        assertThat(inventory.balance(maker, new Key(receiveWarehouse, finished)).onHand()).isEqualByComparingTo("2");
    }

    private void receiveRaw(String batch, String cost, String... serials) {
        long id = Ids.next();
        inventory.receive(maker, new Posting(new Key(issueWarehouse, raw), new Source("opening_entry", id, id), today, period, "opening_in"),
                BigDecimal.valueOf(serials.length), new BigDecimal(cost), new StockSelection(null, batch, today, null, bin, List.of(serials)), null);
    }

    private Map<String, Object> issueDraft(String direction, String qty, Long origin, String... serials) {
        return documents.create(maker, Type.ISSUE, key(), new Draft(order, issueWarehouse, today, direction, null, null,
                List.of(new InputLine(material, null, qty, new StockSelection(null, null, null, null, bin, List.of(serials)), origin))));
    }

    private long receiptDraft(String qty) {
        return number(documents.create(maker, Type.RECEIPT, key(), new Draft(order, receiveWarehouse, today, null, null, null,
                List.of(new InputLine(null, null, qty, null, null)))), "id");
    }

    private Map<String, Object> post(Type type, long id) {
        command(maker, type, id, "submit", 0);
        command(reviewer, type, id, "approve", 1);
        return command(maker, type, id, "post", 2);
    }

    private Map<String, Object> command(CompanyAccess actor, Type type, long id, String action, long revision) {
        return documents.command(actor, type, id, action, key(), new Revision(revision, null, null));
    }

    private long createReverse(Type type, long id) {
        return number(reversals.create(maker, key(), new ReversalModels.Input(type.head, id, today, "生产单据冲销")), "id");
    }

    private void reverse(Type type, long id) {
        long reversal = createReverse(type, id);
        reversals.command(reviewer, reversal, "approve", key(), new ReversalModels.Version(0L, null));
        reversals.command(maker, reversal, "post", key(), new ReversalModels.Version(1L, null));
    }

    private Balance rawBalance() { return inventory.balance(maker, new Key(issueWarehouse, raw)); }

    private String serialStatus(String serial) {
        return jdbc.queryForObject("SELECT status FROM inv_serial WHERE tenant_id=? AND company_id=? AND sku_id=? AND serial_no=?",
                String.class, tenant, company, raw, serial);
    }

    private BigDecimal materialQuantity(String field) {
        return jdbc.queryForObject("SELECT " + field + " FROM mfg_work_order_material WHERE id=?", BigDecimal.class, material);
    }

    private void reject(String code, Runnable run) {
        assertThatThrownBy(run::run).isInstanceOfSatisfying(ApiException.class, problem -> assertThat(problem.getErrorCode()).isEqualTo(code));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstLine(Map<String, Object> document) { return ((List<Map<String, Object>>) document.get("lines")).getFirst(); }

    private long number(Map<String, Object> fields, String column) { return Long.parseLong(fields.get(column).toString()); }

    private String key() { return UUID.randomUUID().toString(); }
}
