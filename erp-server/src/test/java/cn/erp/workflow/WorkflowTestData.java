package cn.erp.workflow;

import java.time.*;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;

import cn.erp.identity.*;
import cn.erp.salesorder.*;
import cn.erp.shared.*;

final class WorkflowTestData {
    final JdbcTemplate jdbc;
    final AccessService access;
    final SalesOrderService sales;
    final WorkflowModels models;
    final long tenant = Ids.next(),
            company = Ids.next(),
            warehouse = Ids.next(),
            customer = Ids.next(),
            unit = Ids.next(),
            sku = Ids.next(),
            department = Ids.next(),
            parentDepartment = Ids.next(),
            post = Ids.next();
    final long maker = Ids.next(),
            manager = Ids.next(),
            financeA = Ids.next(),
            financeB = Ids.next();
    final LocalDate day = LocalDate.of(2026, 11, 15);

    WorkflowTestData(
            JdbcTemplate jdbc,
            AccessService access,
            SalesOrderService sales,
            WorkflowModels models) {
        this.jdbc = jdbc;
        this.access = access;
        this.sales = sales;
        this.models = models;
        jdbc.update(
                "INSERT INTO tenant(id,code,name) VALUES(?,?,'流程功能验收企业')", tenant, "WF" + tenant);
        jdbc.update(
                "INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','流程公司')",
                company,
                tenant);
        jdbc.update(
                "INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",
                Ids.next(),
                tenant,
                company);
        for (long user : List.of(maker, manager, financeA, financeB)) {
            jdbc.update(
                    "INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash)"
                        + " VALUES(?,?,?,?,?)",
                    user,
                    tenant,
                    "u" + user,
                    user == maker
                            ? "发起人"
                            : user == manager ? "部门经理" : user == financeA ? "财务甲" : "财务乙",
                    "非登录测试摘要");
            long role = Ids.next();
            String code = user == financeA || user == financeB ? "FINANCE_" + user : "ROLE_" + user;
            jdbc.update(
                    "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'验收角色')",
                    role,
                    tenant,
                    code);
            jdbc.update(
                    "INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",
                    Ids.next(),
                    tenant,
                    user,
                    role);
            for (long permission : jdbc.queryForList("SELECT id FROM sys_permission", Long.class))
                jdbc.update(
                        "INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)"
                            + " VALUES(?,?,?,?)",
                        Ids.next(),
                        tenant,
                        role,
                        permission);
            jdbc.update(
                    "INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id)"
                        + " VALUES(?,?,?,'company',?)",
                    Ids.next(),
                    tenant,
                    user,
                    company);
            jdbc.update(
                    "INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(?,?,?)",
                    tenant,
                    company,
                    user);
        }
        jdbc.update(
                "INSERT INTO"
                    + " org_department(id,tenant_id,company_id,code,name,leader_user_id,path,depth)"
                    + " VALUES(?,?,?,'P','总公司',?, ?,1)",
                parentDepartment,
                tenant,
                company,
                financeB,
                "/" + parentDepartment + "/");
        jdbc.update(
                "INSERT INTO"
                    + " org_department(id,tenant_id,company_id,parent_id,code,name,leader_user_id,path,depth)"
                    + " VALUES(?,?,?,?,'D','销售部门',?,?,2)",
                department,
                tenant,
                company,
                parentDepartment,
                manager,
                "/" + parentDepartment + "/" + department + "/");
        for (long user : List.of(maker, manager, financeA, financeB))
            jdbc.update(
                    "INSERT INTO"
                        + " org_user_department(tenant_id,company_id,user_id,department_id,is_primary)"
                        + " VALUES(?,?,?,?,1)",
                    tenant,
                    company,
                    user,
                    department);
        jdbc.update(
                "INSERT INTO org_post(id,tenant_id,code,name) VALUES(?,?,'FINANCE','财务岗位')",
                post,
                tenant);
        for (long user : List.of(financeA, financeB))
            jdbc.update(
                    "INSERT INTO org_user_post(tenant_id,company_id,user_id,post_id)"
                        + " VALUES(?,?,?,?)",
                    tenant,
                    company,
                    user,
                    post);
        jdbc.update(
                "INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','流程仓库')",
                warehouse,
                tenant,
                company);
        jdbc.update(
                "INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','流程客户')",
                customer,
                tenant);
        jdbc.update(
                "INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive)"
                    + " VALUES(?,?,?,'customer',1)",
                Ids.next(),
                tenant,
                customer);
        long category = Ids.next(), product = Ids.next();
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')", unit, tenant);
        jdbc.update(
                "INSERT INTO product_category(id,tenant_id,code,name,path)"
                    + " VALUES(?,?,'C','流程分类','/')",
                category,
                tenant);
        jdbc.update(
                "INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id)"
                    + " VALUES(?,?,?,'P','流程商品',?)",
                product,
                tenant,
                category,
                unit);
        jdbc.update(
                "INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price)"
                    + " VALUES(?,?,?,'S','流程规格',100)",
                sku,
                tenant,
                product);
        jdbc.update(
                "INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base)"
                    + " VALUES(?,?,?,?,1,1)",
                Ids.next(),
                tenant,
                sku,
                unit);
        jdbc.update(
                "INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date)"
                    + " VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",
                Ids.next(),
                tenant,
                company);
        jdbc.update(
                "INSERT INTO"
                    + " inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,on_hand_qty)"
                    + " VALUES(?,?,?,?,?,10000)",
                Ids.next(),
                tenant,
                company,
                warehouse,
                sku);
        jdbc.update(
                "INSERT INTO"
                    + " inventory_cost(id,tenant_id,company_id,warehouse_id,sku_id,qty,total_cost)"
                    + " VALUES(?,?,?,?,?,10000,100000)",
                Ids.next(),
                tenant,
                company,
                warehouse,
                sku);
    }

    CompanyAccess actor(long user) {
        return access.forUser(tenant, user, company);
    }

    long order(String qty, String price) {
        var line =
                new SalesOrderModels.Line(
                        null,
                        "" + sku,
                        "" + warehouse,
                        "" + unit,
                        qty,
                        price,
                        null,
                        "0",
                        null,
                        null);
        return Long.parseLong(
                sales.create(
                                actor(maker),
                                key(),
                                new SalesOrderModels.Input(
                                        "" + customer,
                                        day,
                                        day.plusDays(1),
                                        true,
                                        "0",
                                        List.of(line),
                                        List.of(),
                                        null,
                                        null,
                                        null))
                        .get("id")
                        .toString());
    }

    long bind(SimpleModel.Node root) {
        return bind(root, "sales_order");
    }

    long bind(SimpleModel.Node root, String type) {
        var actor = actor(maker);
        long model =
                Long.parseLong(
                        models.save(
                                        actor,
                                        null,
                                        key(),
                                        new WorkflowModels.Input(
                                                company,
                                                "F" + Ids.next(),
                                                "流程验收",
                                                null,
                                                type,
                                                root,
                                                0L))
                                .get("id")
                                .toString());
        models.change(actor, model, "publish", 0, key());
        models.bind(
                actor,
                null,
                key(),
                new WorkflowModels.Binding(company, type, model, 0, null, true, 0L));
        return model;
    }

    long submit(long order) {
        sales.command(
                actor(maker),
                order,
                "submit",
                key(),
                new SalesOrderModels.Command(version(order), "提交验收", null));
        return jdbc.queryForObject(
                "SELECT wf_instance_id FROM sales_order WHERE tenant_id=? AND id=?",
                Long.class,
                tenant,
                order);
    }

    long version(long order) {
        return jdbc.queryForObject(
                "SELECT version FROM sales_order WHERE tenant_id=? AND id=?",
                Long.class,
                tenant,
                order);
    }

    String status(long order) {
        return jdbc.queryForObject(
                "SELECT status FROM sales_order WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                order);
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static SimpleModel.Node root(SimpleModel.Node child) {
        return new SimpleModel.Node("root", "start", "发起人", null, null, null, null, child, false);
    }

    static SimpleModel.Node approve(
            String id, List<Long> users, String multi, SimpleModel.Node child) {
        return new SimpleModel.Node(
                id,
                "approver",
                "审批" + id,
                new SimpleModel.Assignee("users", users, null, null, null, null, null, "admin"),
                multi,
                null,
                null,
                child,
                false);
    }

    void cleanup(org.flowable.engine.ProcessEngine engine) {
        for (var deployment :
                engine.getRepositoryService()
                        .createDeploymentQuery()
                        .deploymentTenantId(Long.toString(tenant))
                        .list())
            engine.getRepositoryService().deleteDeployment(deployment.getId(), true);
        for (var task :
                engine.getTaskService()
                        .createTaskQuery()
                        .taskTenantId(Long.toString(tenant))
                        .list())
            if (task.getExecutionId() == null)
                engine.getTaskService().deleteTask(task.getId(), true);
        jdbc.update("UPDATE org_department SET parent_id=NULL WHERE tenant_id=?", tenant);
        jdbc.update("UPDATE purchase_order_fee SET recognized_receipt_id=NULL WHERE tenant_id=?", tenant);
        for (String table :
                List.of(
                        "einv_inbound_match","einv_inbound","inbound_invoice_lock",
                        "sys_login_log",
                        "sys_api_access_log",
                        "sys_api_error_log",
                        "notify_send_log",
                        "notify_message",
                        "alert_event","alert_sku_safety_stock","alert_rule",
                        "job_item_failure",
                        "job_run_log",
                        "job_definition",
                        "wf_copy",
                        "wf_task_action",
                        "wf_task_ext",
                        "wf_instance",
                        "wf_business_binding",
                        "wf_model_version",
                        "wf_model",
                        "wf_category",
                        "inbox_event",
                        "outbox_event",
                        "idempotency_record",
                        "audit_log",
                        "approval_record",
                        "inventory_reservation_change",
                        "inventory_reservation",
                        "inv_movement_hold","inventory_movement",
                        "cost_adjustment",
                        "ar_collection_followup","ar_collection_plan","ar_bad_debt","credit_increase","ar_credit_limit","ar_customer_lock","ar_aging_config",
                        "journal_line",
                        "journal",
                        "ledger_balance",
                        "cash_account_balance",
                        "settlement_allocation",
                        "partner_open_item",
                        "receivable",
                        "refund_request",
                        "receipt",
                        "payment",
                        "cash_account",
                        "stock_adjustment_line",
                        "stock_adjustment",
                        "stock_transfer_event_line","stock_transfer_line","stock_transfer",
                        "document_reversal",
                        "purchase_fee_cost","purchase_fee_allocation","purchase_fee_doc_line","purchase_fee_doc",
                        "price_adjustment_cost","price_adjustment_line","price_adjustment","document_change",
                        "purchase_return_line","purchase_return","payable","goods_receipt_line","goods_receipt",
                        "purchase_order_fee",
                        "purchase_order_line",
                        "purchase_order",
                        "inventory_balance",
                        "inventory_cost",
                        "shipment_line",
                        "shipment",
                        "sales_order_fee",
                        "sales_order_line",
                        "sales_order",
                        "document_sequence",
                        "unit_conversion",
                        "sku",
                        "product",
                        "product_category",
                        "unit",
                        "partner_role",
                        "business_partner",
                        "accounting_period",
                        "org_user_data_scope",
                        "warehouse",
                        "company_policy",
                        "org_role_data_scope_dept",
                        "org_role_data_scope",
                        "org_user_post",
                        "org_user_department",
                        "org_user_company",
                        "org_department",
                        "org_post",
                        "sys_user_role",
                        "sys_role_permission",
                        "sys_role",
                        "sys_user",
                        "job_run_log",
                        "job_definition",
                        "company"))
        {
            if(table.equals("payable")) jdbc.update("DELETE FROM payable WHERE tenant_id=? AND posting_action='reverse'",tenant);
            if(table.equals("journal")) jdbc.update("DELETE FROM journal WHERE tenant_id=? AND posting_action='reverse'",tenant);
            if(table.equals("inventory_movement")) jdbc.update("DELETE FROM inventory_movement WHERE tenant_id=? AND posting_action='reverse'",tenant);
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?", tenant);
    }
}
