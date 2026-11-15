package cn.erp.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;

/** 打印来源只给出当前可查看单据的单号，不把内部行ID当作业务编号。 */
@Service
public class PrintSources {
    private final JdbcTemplate jdbc;
    private final DocumentAccess documents;

    public PrintSources(JdbcTemplate jdbc, DocumentAccess documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    public List<Map<String, Object>> list(CompanyAccess scope, String type, long id, Map<String, Object> header) {
        List<Long> ids = new ArrayList<>();
        String source = switch (type) {
            case "shipment" -> "sales_order";
            case "goods_receipt", "payment" -> "purchase_order";
            case "sales_return" -> "shipment";
            case "purchase_return" -> "goods_receipt";
            case "receipt" -> "sales_order";
            case "refund_request" -> "receipt";
            case "document_reversal" -> String.valueOf(header.get("source_type"));
            default -> null;
        };
        if (source == null || !scope.hasPermission(documents.permission(source, "view"))) { return List.of(); }
        String sql = switch (type) {
            case "shipment" -> "SELECT DISTINCT p.order_id FROM shipment_line l JOIN sales_order_line p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.id=l.order_line_id WHERE l.tenant_id=? AND l.company_id=? AND l.shipment_id=?";
            case "goods_receipt" -> "SELECT DISTINCT p.order_id FROM goods_receipt_line l JOIN purchase_order_line p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.id=l.order_line_id WHERE l.tenant_id=? AND l.company_id=? AND l.receipt_id=?";
            case "sales_return" -> "SELECT DISTINCT p.shipment_id FROM sales_return_line l JOIN shipment_line p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.id=l.shipment_line_id WHERE l.tenant_id=? AND l.company_id=? AND l.return_id=?";
            case "purchase_return" -> "SELECT DISTINCT p.receipt_id FROM purchase_return_line l JOIN goods_receipt_line p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.id=l.receipt_line_id WHERE l.tenant_id=? AND l.company_id=? AND l.return_id=?";
            default -> null;
        };
        if (sql != null) { ids.addAll(jdbc.queryForList(sql, Long.class, scope.tenantId(), scope.companyId(), id)); }
        else {
            Object sourceId = header.get(type.equals("document_reversal") ? "source_id" : type.equals("refund_request") ? "original_receipt_id" : "related_order_id");
            if (sourceId instanceof Number value) { ids.add(value.longValue()); }
        }
        var result = new ArrayList<Map<String, Object>>();
        for (long sourceId : ids.stream().distinct().toList()) {
            try {
                var value = documents.view(scope, source, sourceId);
                Object number = value.get("documentNo");
                if (number != null) { result.add(Map.of("documentType", source, "documentNo", number.toString())); }
            } catch (ApiException error) {
                if (error.getStatus() != 403 && error.getStatus() != 404) { throw error; }
                // 来源范围可以与当前单据不同；不可见的来源不能出现在打印件上。
            }
        }
        return List.copyOf(result);
    }
}
