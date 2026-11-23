package cn.erp.shared;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等结果同样遵守当前仓库与部门范围，避免授权收窄后从历史响应读取单据。 */
@Component
public class CommandResultAccess {
    private final JdbcTemplate jdbc;
    public CommandResultAccess(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public void verify(ActorScope actor,String operation,Object response) {
        int separator=operation.indexOf('.');
        String prefix=separator>0?operation.substring(0,separator):operation;
        if(ManufacturingReversalPort.supports(prefix)) {
            verifyProduction(actor,prefix,response instanceof Map<?,?> fields?fields.get("id"):response);
            return;
        }
        if(!(actor instanceof WarehouseScope scope) || !(response instanceof Map<?,?> row)) {return;}
        if(operation.startsWith("sales_price_adjustment.")&&row.get("id")!=null) {
            if(!(actor instanceof PermissionScope permissions)||!permissions.hasPermission("sales_price_adjustment:view"))throw new ApiException(403,"FORBIDDEN","无权查看销售调价结果");
            var headers=jdbc.queryForList("SELECT * FROM price_adjustment WHERE tenant_id=? AND company_id=? AND id=? AND kind='sales'",actor.tenantId(),actor.companyId(),row.get("id"));
            if(headers.isEmpty())throw missing();PriceAdjustmentScope.require(jdbc,actor,headers.getFirst());
            if(row.get("lines") instanceof List<?> lines)for(Object line:lines)if(line instanceof Map<?,?> fields&&fields.get("redInvoices") instanceof List<?> invoices)
                for(Object invoice:invoices)if(invoice instanceof Map<?,?> reference) {
                    if(!permissions.hasPermission("invoice_registration:view"))throw new ApiException(403,"FORBIDDEN","无权读取原票摘要");
                    if(reference.get("invoiceId")==null)throw missing();
                    SalesInvoiceScope.registration(jdbc,actor,Long.parseLong(reference.get("invoiceId").toString()));
                }
            return;
        }
        if(java.util.Set.of("einv.create","einv.submit","einv.red_issue").contains(operation)&&row.get("id")!=null)
            for(long ar:jdbc.queryForList("SELECT receivable_id FROM einv_issue_line WHERE tenant_id=? AND company_id=? AND request_id=?",Long.class,actor.tenantId(),actor.companyId(),row.get("id")))
                SalesInvoiceScope.receivable(jdbc,actor,ar);
        if(operation.startsWith("invoice_registration.")&&row.get("id")!=null)
            SalesInvoiceScope.registration(jdbc,actor,Long.parseLong(row.get("id").toString()));
        if(operation.startsWith("invoice_red.")) {
            String action=operation.substring(operation.indexOf('.')+1);
            if(!(actor instanceof PermissionScope permissions)||!permissions.hasPermission("invoice_red:view")
                    ||!permissions.hasPermission("invoice_registration:view")||!permissions.hasPermission("invoice_red:"+action))
                throw new ApiException(403,"FORBIDDEN","无权读取当前红字登记操作结果");
            SalesInvoiceScope.red(jdbc,actor,row.get("id"));return;
        }
        if(operation.startsWith("einv.inbound_")) {
            String permission=operation.equals("einv.inbound_register")||operation.equals("einv.inbound_update")?"einv:inbound_register":operation.equals("einv.inbound_void")?"einv:inbound_void":"einv:inbound";
            if(!(actor instanceof PermissionScope permissions)||!permissions.hasPermission("einv:view")||!permissions.hasPermission("einv:inbound")||!permissions.hasPermission(permission))throw new ApiException(403,"FORBIDDEN","无权读取当前进项操作结果");
            InboundInvoiceScope.require(jdbc,actor,row.get("id"));return;
        }
        if(operation.startsWith("credit_limit.")||operation.startsWith("credit_increase.")||operation.startsWith("bad_debt.")||operation.startsWith("aging_config.")) {
            String action=operation.substring(operation.indexOf('.')+1);String permission=operation.startsWith("bad_debt.")?(java.util.Set.of("approve","reject").contains(action)?"bad_debt:approve":"bad_debt:edit"):operation.startsWith("aging_config.")?"aging_config:edit":java.util.Set.of("approve","reject").contains(action)||action.equals("cancel")&&row.get("approvedBy")!=null?"credit_limit:approve":"credit_limit:edit";
            FinancialScope.require(actor,permission);return;
        }
        if(operation.startsWith("collection.")) {
            if(!(actor instanceof PermissionScope permissions)||!permissions.hasPermission("collection:edit")||!scope.allWarehouses())throw new ApiException(403,"FORBIDDEN","无权读取催收操作结果");
            if(row.get("id")!=null) {var plans=jdbc.queryForList("SELECT open_item_id FROM ar_collection_plan WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),row.get("id"));if(plans.isEmpty())throw missing();OrgOwnership.requireItem(jdbc,actor,plans.getFirst().get("open_item_id"));}return;
        }
        if(java.util.Set.of("period.close","period.reopen").contains(operation)&&jdbc.queryForObject("SELECT COUNT(*) FROM gl_account_book WHERE tenant_id=? AND company_id=? AND status='active'",Long.class,actor.tenantId(),actor.companyId())>0)FinancialScope.require(actor,"gl_closing:"+operation.substring(operation.indexOf('.')+1));
        if(operation.equals("reconciliation.run")&&row.get("id")!=null) {var runs=jdbc.queryForList("SELECT check_type FROM reconciliation_run WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),row.get("id"));if(runs.isEmpty())throw missing();if(runs.getFirst().get("check_type").toString().startsWith("gl_"))FinancialScope.require(actor,"gl_ledger:view");}
        if(operation.equals("export.create")&&row.get("taskId")!=null) {
            var exports=jdbc.queryForList("SELECT export_type FROM export_job WHERE tenant_id=? AND company_id=? AND id=? AND created_by=?",actor.tenantId(),actor.companyId(),row.get("taskId"),actor.userId());if(exports.isEmpty())throw missing();String type=exports.getFirst().get("export_type").toString();if(FinancialExportPort.supports(type)) {FinancialScope.require(actor,"gl_export:create");FinancialScope.require(actor,type.equals("gl-vouchers")?"gl_voucher:view":java.util.Set.of("gl-balance_sheet","gl-income_statement","gl-cash_flow").contains(type)?"gl_report:view":"gl_ledger:view");return;}
        }
        if(operation.startsWith("gl_")) {String resource=operation.substring(0,operation.indexOf('.'));String action=operation.substring(operation.indexOf('.')+1);String permission=switch(resource) {case "gl_config"->action.equals("confirm")?"gl_config:confirm":"gl_config:edit";case "gl_opening"->action.equals("save")?"gl_opening:edit":"gl_opening:activate";case "gl_voucher"->switch(action) {case "save","submit","withdraw","delete","organize"->"gl_voucher:edit";case "approve","reject"->"gl_voucher:approve";case "reverse"->"gl_voucher:reverse";default->"gl_voucher:post";};default->"gl_sync:run";};FinancialScope.require(actor,permission);return;}
        if(operation.equals("role.assign_data_scope")&&!OrgOwnership.scope(actor).allDepartments()) throw new ApiException(403,"FORBIDDEN","没有查看部门范围管理结果的权限");
        String document=operation.startsWith("reversal.")?"document_reversal":operation.substring(0,operation.indexOf('.'));
        if(OrgOwnership.TABLES.contains(document)&&row.get("id")!=null) OrgOwnership.require(jdbc,actor,document,row.get("id"));
        if(document.equals("purchase_fee_doc") && row.get("id")!=null) OrgOwnership.requireSource(jdbc,actor,"purchase_fee_doc",row.get("id"));
        if(document.equals("price_adjustment") && row.get("id")!=null) {
            var headers=jdbc.queryForList("SELECT * FROM price_adjustment WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),row.get("id"));
            if(headers.isEmpty()) throw missing();
            PriceAdjustmentScope.require(jdbc,actor,headers.getFirst());
        }
        if(operation.startsWith("settlement.")) {
            if(row.get("id")!=null) OrgOwnership.requireAllocation(jdbc,actor,row.get("id"));
            if(row.get("items") instanceof List<?> values) for(Object value:values) if(value instanceof Map<?,?> item && item.get("id")!=null) OrgOwnership.requireAllocation(jdbc,actor,item.get("id"));
        }
        if(operation.equals("user.assign_organization")) {
            Long count=jdbc.queryForObject("SELECT COUNT(*) FROM org_user_data_scope WHERE tenant_id=? AND company_id=? AND user_id=?",Long.class,scope.tenantId(),scope.companyId(),row.get("userId"));
            if(count==null || count==0) throw missing();
            return;
        }
        if(java.util.Set.of("period.close","period.reopen","outbox.replay","reconciliation.run").contains(operation)&&(!scope.allWarehouses()||!OrgOwnership.scope(actor).allDepartments())) {throw new ApiException(403,"FORBIDDEN","操作结果需要整家公司数据范围");}
        if(operation.startsWith("reversal.")) {
            String type=String.valueOf(row.get("sourceType"));
            if(type.equals("purchase_fee_doc")) OrgOwnership.requireSource(jdbc,actor,type,row.get("sourceId"));
            if(java.util.Set.of("price_adjustment","sales_price_adjustment").contains(type)) {
                var prices=jdbc.queryForList("SELECT * FROM price_adjustment WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),row.get("sourceId"));
                if(prices.isEmpty())throw missing();
                PriceAdjustmentScope.require(jdbc,actor,prices.getFirst());
            }
            if(ManufacturingReversalPort.supports(type)) {verifyProduction(actor,type,row.get("sourceId"));return;}
            if(!(actor instanceof PermissionScope permissions)||!permissions.hasPermission(type+":view")) {throw new ApiException(403,"FORBIDDEN","没有查看冲销来源单据的权限");}
            if(!scope.allWarehouses()) {
                if(java.util.Set.of("shipment","sales_return","goods_receipt","purchase_return","stock_adjustment","stock_misc_doc").contains(type)) {
                    var values=jdbc.queryForList("SELECT warehouse_id FROM "+type+" WHERE tenant_id=? AND company_id=? AND id=?",Long.class,scope.tenantId(),scope.companyId(),row.get("sourceId"));
                    if(values.isEmpty()) {throw missing();}warehouse(scope,values.getFirst());
                }
                if(row.get("warehouseIds") instanceof List<?> values) {for(Object value:values) {warehouse(scope,value);}}
            }
            return;
        }
        if(scope.allWarehouses()) {return;}
        Object id=row.get("id");
        if(id==null) {return;}
        if(operation.startsWith("sales_order.")||operation.startsWith("purchase_order.")) {
            String lineTable=operation.startsWith("sales_order.")?"sales_order_line":"purchase_order_line";
            var warehouses=jdbc.queryForList("SELECT warehouse_id FROM "+lineTable+" WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0",Long.class,scope.tenantId(),scope.companyId(),id);
            if(warehouses.isEmpty() || warehouses.stream().anyMatch(w->w==null||!scope.allowsWarehouse(w))) {throw missing();}
            if(row.get("lines") instanceof List<?> lines) {
                for(Object line:lines) {if(line instanceof Map<?,?> fields) {warehouse(scope,fields.get("warehouseId"));}}
            }
        } else if(operation.startsWith("stock_transfer.")) {
            var values=jdbc.queryForList("SELECT from_warehouse_id,to_warehouse_id FROM stock_transfer WHERE tenant_id=? AND company_id=? AND id=?",scope.tenantId(),scope.companyId(),id);
            if(values.isEmpty()) {throw missing();}
            warehouse(scope,values.getFirst().get("from_warehouse_id"));warehouse(scope,values.getFirst().get("to_warehouse_id"));
            warehouse(scope,row.get("fromWarehouseId"));warehouse(scope,row.get("toWarehouseId"));
        } else {
            String table=warehouseDocumentTable(document);
            if(table==null) return;
            var warehouses=jdbc.queryForList("SELECT warehouse_id FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=?",Long.class,scope.tenantId(),scope.companyId(),id);
            if(warehouses.isEmpty()) {throw missing();}
            warehouse(scope,warehouses.getFirst());
            if(row.containsKey("warehouseId")) {warehouse(scope,row.get("warehouseId"));}
        }
    }

    private void verifyProduction(ActorScope actor,String type,Object id) {
        if(!(actor instanceof PermissionScope permission)||!permission.hasPermission(ManufacturingReversalPort.viewPermission(type)))
            throw new ApiException(403,"FORBIDDEN","没有查看生产单据的权限");
        String table=switch(type) {
            case "mfg_issue_doc" -> "mfg_issue_doc";
            case "mfg_receipt_doc" -> "mfg_receipt_doc";
            default -> throw missing();
        };
        var rows=jdbc.queryForList("SELECT d.id FROM "+table+" d WHERE d.tenant_id=? AND d.company_id=? AND d.id=? AND "+ManufacturingScope.stockCondition(actor,"d"),
                Long.class,actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty())throw missing();
    }

    private static String warehouseDocumentTable(String document) {
        return switch(document) {
            case "shipment" -> "shipment";
            case "sales_return" -> "sales_return";
            case "goods_receipt" -> "goods_receipt";
            case "purchase_return" -> "purchase_return";
            case "stock_adjustment" -> "stock_adjustment";
            case "stock_count" -> "stock_count";
            case "stock_misc_doc" -> "stock_misc_doc";
            case "qc_inspection" -> "qc_inspection";
            case "price_adjustment" -> "price_adjustment";
            case "purchase_fee_doc" -> "purchase_fee_doc";
            default -> null;
        };
    }

    private void warehouse(WarehouseScope scope,Object id) {
        if(id==null || !scope.allowsWarehouse(Long.parseLong(id.toString()))) {throw missing();}
    }
    private ApiException missing() {return new ApiException(404,"NOT_FOUND","单据不存在或无权查看");}
}
