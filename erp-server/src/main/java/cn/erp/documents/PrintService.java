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
        DocumentAccess.require(scope, documents.permission(type, "print"));
        Map<String, Object> document = new LinkedHashMap<>(documents.view(scope, type, id));
        decorateFields(scope,type,document);
        // 单据查询已完成权限和范围检查；原始表头仅定位制单人和受权来源，不能合并到响应。
        Map<String, Object> header = jdbc.queryForMap("SELECT * FROM " + type + " WHERE tenant_id=? AND company_id=? AND id=?",
                scope.tenantId(), scope.companyId(), id);
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
        if (type.equals("goods_receipt") || type.equals("shipment")) {
            String table = type.equals("goods_receipt") ? "purchase_order_fee" : "sales_order_fee";
            String reference = type.equals("goods_receipt") ? "recognized_receipt_id" : "recognized_shipment_id";
            document.put("recognizedFees", jdbc.queryForList("SELECT fee_type,bearer,amount,total_amount,tax_rate,untaxed_amount,tax_amount FROM "
                    + table + " WHERE tenant_id=? AND company_id=? AND " + reference + "=? ORDER BY id",
                    scope.tenantId(), scope.companyId(), id).stream().map(FinanceSupport::row).toList());
        }
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
        audit.record(scope, "document.print", type, id, null, Map.of("documentType", type));
        return result;
    }

    private void decorateFields(CompanyAccess scope,String type,Map<String,Object> document) {
        if (!List.of("sales_order","purchase_order").contains(type)) return;
        project(document,customFields.fields(scope.tenantId(),type));
        if (type.equals("sales_order") && document.get("lines") instanceof List<?> lines) {
            var fields = customFields.fields(scope.tenantId(),"sales_order_line");
            document.put("lines",lines.stream().map(value -> {
                if (!(value instanceof Map<?,?> row)) return value;
                var line=new LinkedHashMap<String,Object>();
                row.forEach((key,item)->line.put(key.toString(),item));
                project(line,fields);
                return line;
            }).toList());
        }
    }

    private void project(Map<String,Object> row,List<CustomFields.Field> fields) {
        if (!(row.remove("extJson") instanceof Map<?,?> values)) return;
        var shown=new LinkedHashMap<String,Object>();
        for (var field:fields) {
            if (field.printVisible() && values.containsKey(field.fieldKey())) shown.put(field.label(),values.get(field.fieldKey()));
        }
        if(!shown.isEmpty()) row.put("printCustomFields",shown);
    }
}
