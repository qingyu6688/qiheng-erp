package cn.erp.documents;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.customfield.CustomFields;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.AuditService;

@Service
public class PrintService {
    private final DocumentAccess documents;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PrintSources sources;
    private final CustomFields customFields;

    public PrintService(DocumentAccess documents, JdbcTemplate jdbc, AuditService audit, PrintSources sources, CustomFields customFields) {
        this.documents = documents;
        this.jdbc = jdbc;
        this.audit = audit;
        this.sources = sources;
        this.customFields = customFields;
    }

    @Transactional
    public Map<String, Object> get(CompanyAccess scope, String type, long id) {
        return prepare(scope,type,id,false,true);
    }

    /** 自定义模板按字段编码取值，修改字段显示名称不会改变模板引用。 */
    @Transactional(readOnly=true)
    public Map<String, Object> templateData(CompanyAccess scope, String type, long id) {
        return prepare(scope,type,id,true,false);
    }

    private Map<String, Object> prepare(CompanyAccess scope, String type, long id, boolean stableFields, boolean record) {
        DocumentAccess.require(scope, documents.permission(type, "print"));
        Map<String, Object> document = new LinkedHashMap<>(documents.view(scope, type, id));
        decorateFields(scope,type,document,stableFields);
        // 单据查询已完成权限和范围检查；原始表头仅定位制单人和受权来源，不能合并到响应。
        Map<String, Object> header = jdbc.queryForMap("SELECT * FROM " + documents.table(type) + " WHERE tenant_id=? AND company_id=? AND id=?",
                scope.tenantId(), scope.companyId(), id);
        if(stableFields && scope.hasPermission("cash_account:view") && scope.hasPermission("cash_account_no:view")) {
            copyAccountNumber(scope,header,document,"cash_account_id","accountNo");
            if(type.equals("account_transfer")) {
                copyAccountNumber(scope,header,document,"from_account_id","fromAccountNo");
                copyAccountNumber(scope,header,document,"to_account_id","toAccountNo");
            }
        }
        document.put("sourceDocuments", sources.list(scope, type, id, header));
        for (var actor : Map.of("createdBy", "created_by", "approvedBy", "approved_by", "confirmedBy", "confirmed_by", "postedBy", "posted_by").entrySet()) {
            if (document.get(actor.getKey() + "Name") != null) { continue; }
            Object operator = document.get(actor.getKey());
            if (operator == null) { operator = header.get(actor.getValue()); }
            if (operator != null) {
                var names = jdbc.queryForList("SELECT display_name FROM sys_user WHERE tenant_id=? AND id=?", String.class, scope.tenantId(), operator);
                if (!names.isEmpty()) { document.put(actor.getKey() + "Name", names.getFirst()); }
            }
        }
        if (type.equals("shipment")) {
            document.put("recognizedFees", jdbc.queryForList("SELECT fee_type,bearer,amount,total_amount,tax_rate,untaxed_amount,tax_amount FROM sales_order_fee"
                    + " WHERE tenant_id=? AND company_id=? AND recognized_shipment_id=? ORDER BY id",
                    scope.tenantId(), scope.companyId(), id).stream().map(FinanceSupport::row).toList());
        }
        if(type.equals("goods_receipt")) document.put("recognizedFees",jdbc.queryForList("SELECT f.fee_type,f.bearer,f.amount,f.total_amount,f.tax_rate,f.untaxed_amount,f.tax_amount FROM purchase_order_fee f"
                +" WHERE f.tenant_id=? AND f.company_id=? AND f.recognized_receipt_id=?"
                +" AND NOT EXISTS(SELECT 1 FROM purchase_fee_doc_line nl JOIN purchase_fee_doc nh ON nh.tenant_id=nl.tenant_id AND nh.company_id=nl.company_id AND nh.id=nl.document_id"
                +" WHERE nl.tenant_id=f.tenant_id AND nl.company_id=f.company_id AND nl.order_fee_id=f.id AND nh.source_receipt_id=?)"
                +" UNION ALL SELECT l.fee_type,'buyer',l.amount,l.amount,l.tax_rate,l.untaxed_amount,l.tax_amount FROM purchase_fee_doc_line l"
                +" JOIN purchase_fee_doc h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.document_id"
                +" WHERE h.tenant_id=? AND h.company_id=? AND h.source_kind='receipt' AND h.source_receipt_id=?",
                scope.tenantId(),scope.companyId(),id,id,scope.tenantId(),scope.companyId(),id).stream().map(FinanceSupport::row).toList());
        List<Map<String, Object>> approvals = jdbc.queryForList("""
                SELECT a.action,a.revision,a.comment,a.created_at,u.display_name AS operator_name FROM approval_record a
                JOIN sys_user u ON u.tenant_id=a.tenant_id AND u.id=a.operator_id
                WHERE a.tenant_id=? AND a.company_id=? AND a.document_type=? AND a.document_id=? ORDER BY a.id
                """, scope.tenantId(), scope.companyId(), type, id).stream().map(FinanceSupport::row).toList();
        document.put("approvalRecords", approvals);
        if (document.get("approvedByName") == null) {
            approvals.stream().filter(row -> "approve".equals(row.get("action"))).reduce((first, last) -> last)
                    .ifPresent(row -> document.put("approvedByName", row.get("operatorName")));
        }
        Map<String, Object> company = jdbc.queryForMap("SELECT name,address,phone FROM company WHERE tenant_id=? AND id=? AND status='active'",
                scope.tenantId(), scope.companyId());
        var result = new LinkedHashMap<String, Object>();
        result.put("documentType", type);
        result.put("company", company);
        result.put("document", document);
        result.put("printedAt", Instant.now().toString());
        if(record) audit.record(scope, "document.print", type, id, null, Map.of("documentType", type));
        return result;
    }

    private void copyAccountNumber(CompanyAccess scope,Map<String,Object> raw,Map<String,Object> document,String account,String output) {
        if(raw.get(account)==null) return;
        var rows=jdbc.queryForList("SELECT account_no FROM cash_account WHERE tenant_id=? AND company_id=? AND id=? AND account_no IS NOT NULL",
                String.class,scope.tenantId(),scope.companyId(),raw.get(account));
        if(!rows.isEmpty()) document.put(output,rows.getFirst());
    }

    private void decorateFields(CompanyAccess scope,String type,Map<String,Object> document,boolean stableFields) {
        if (!List.of("sales_order","purchase_order").contains(type)) return;
        project(document,customFields.fields(scope.tenantId(),type),stableFields);
        if (type.equals("sales_order") && document.get("lines") instanceof List<?> lines) {
            var fields = customFields.fields(scope.tenantId(),"sales_order_line");
            document.put("lines",lines.stream().map(value -> {
                if (!(value instanceof Map<?,?> row)) return value;
                var line=new LinkedHashMap<String,Object>();
                row.forEach((key,item)->line.put(key.toString(),item));
                project(line,fields,stableFields);
                return line;
            }).toList());
        }
    }

    private void project(Map<String,Object> row,List<CustomFields.Field> fields,boolean stableFields) {
        if (!(row.remove("extJson") instanceof Map<?,?> values)) return;
        var shown=new LinkedHashMap<String,Object>();
        for (var field:fields) {
            if (field.printVisible() && values.containsKey(field.fieldKey())) shown.put(stableFields?field.fieldKey():field.label(),values.get(field.fieldKey()));
        }
        if(!shown.isEmpty()) row.put("printCustomFields",shown);
    }
}
