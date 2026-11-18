package cn.erp.gl;

import java.time.LocalDate;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

class GlTestData {
    final JdbcTemplate jdbc;
    final long tenant = Ids.next(),
            company = Ids.next(),
            other = Ids.next(),
            maker = Ids.next(),
            reviewer = Ids.next(),
            customer = Ids.next(),
            bank = Ids.next(),
            october = Ids.next(),
            november = Ids.next();
    final Set<String> permissions;

    GlTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        permissions =
                new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission", String.class));
        jdbc.update(
                "INSERT INTO tenant(id,code,name) VALUES(?,?,'总账隔离测试企业')", tenant, "GL" + tenant);
        for (long company : List.of(this.company, other))
            jdbc.update(
                    "INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'测试公司')",
                    company,
                    tenant,
                    company == this.company ? "A" : "B");
        for (long user : List.of(maker, reviewer)) {
            jdbc.update(
                    "INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash)"
                            + " VALUES(?,?,?,'财务测试用户','非登录测试摘要')",
                    user,
                    tenant,
                    "U" + user);
            jdbc.update(
                    "INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id)"
                            + " VALUES(?,?,?,'company',?)",
                    Ids.next(),
                    tenant,
                    user,
                    company);
        }
        jdbc.update(
                "INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','测试客户')",
                customer,
                tenant);
        jdbc.update(
                "INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",
                Ids.next(),
                tenant,
                customer);
        jdbc.update(
                "INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type,currency)"
                        + " VALUES(?,?,?,'BANK','基本户','bank','CNY')",
                bank,
                tenant,
                company);
        for (long id : List.of(october, november))
            jdbc.update(
                    "INSERT INTO"
                        + " accounting_period(id,tenant_id,company_id,period,start_date,end_date)"
                        + " VALUES(?,?,?,?,?,?)",
                    id,
                    tenant,
                    company,
                    id == october ? "202610" : "202611",
                    id == october ? LocalDate.of(2026, 10, 1) : LocalDate.of(2026, 11, 1),
                    id == october ? LocalDate.of(2026, 10, 31) : LocalDate.of(2026, 11, 30));
    }

    CompanyAccess actor(long user) {
        return new CompanyAccess(tenant, user, company, true, Set.of(), permissions);
    }

    long subject(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM gl_subject WHERE tenant_id=? AND company_id=? AND code=?",
                Long.class,
                tenant,
                company,
                code);
    }

    long word() {
        return jdbc.queryForObject(
                "SELECT id FROM gl_voucher_word WHERE tenant_id=? AND company_id=? AND"
                        + " is_default=1",
                Long.class,
                tenant,
                company);
    }

    long cash() {
        return jdbc.queryForObject(
                "SELECT id FROM gl_cash_flow_item WHERE tenant_id=? AND company_id=? AND"
                        + " code='C07'",
                Long.class,
                tenant,
                company);
    }

    GlModels.Query query() {
        return new GlModels.Query(october, october, null, null, null, null, null, null, null, 100);
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    void cleanup() {
        jdbc.update("UPDATE gl_voucher SET reversal_of_id=NULL WHERE tenant_id=?", tenant);
        jdbc.update("UPDATE gl_subject SET parent_id=NULL WHERE tenant_id=?", tenant);
        for (String table :
                List.of(
                        "inbox_event",
                        "outbox_event",
                        "idempotency_record",
                        "audit_log",
                        "gl_sync_error",
                        "gl_period_close",
                        "gl_balance",
                        "gl_opening_balance",
                        "gl_voucher_line",
                        "gl_voucher",
                        "gl_voucher_seq",
                        "gl_voucher_template",
                        "gl_business_mapping",
                        "gl_closing_scheme",
                        "gl_subject",
                        "gl_aux_item",
                        "gl_aux_type",
                        "gl_cash_flow_item",
                        "gl_voucher_word",
                        "gl_digest",
                        "gl_report_template",
                        "gl_export_format",
                        "gl_posting_lock",
                        "gl_account_book",
                        "ar_collection_followup",
                        "ar_collection_plan",
                        "ar_bad_debt",
                        "credit_increase",
                        "ar_credit_limit",
                        "ar_customer_lock",
                        "ar_aging_config",
                        "settlement_allocation",
                        "partner_open_item",
                        "receivable",
                        "journal_line",
                        "journal",
                        "ledger_balance",
                        "ledger_projection_lock",
                        "inventory_reservation_change",
                        "inventory_reservation",
                        "inv_movement_hold","inventory_movement",
                        "cost_adjustment",
                        "receipt",
                        "sales_order_fee",
                        "shipment_line",
                        "shipment",
                        "sales_order_line",
                        "sales_order",
                        "stock_adjustment_line",
                        "stock_adjustment",
                        "opening_entry",
                        "inventory_balance",
                        "inventory_cost",
                        "document_sequence",
                        "company_policy",
                        "unit_conversion",
                        "sku",
                        "product",
                        "product_category",
                        "unit",
                        "cash_account_balance",
                        "cash_account",
                        "partner_role",
                        "business_partner",
                        "accounting_period",
                        "org_department",
                        "org_user_data_scope",
                        "sys_user_role",
                        "sys_role_permission",
                        "sys_role",
                        "sys_user",
                        "warehouse",
                        "company"))
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?", tenant);
    }
}
