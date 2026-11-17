package cn.erp.documents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cn.erp.fulfillment.ShipmentQueries;
import cn.erp.identity.CompanyAccess;
import cn.erp.invoicing.InvoiceService;
import cn.erp.opening.OpeningService;
import cn.erp.purchasefinance.AccountTransferService;
import cn.erp.purchasefinance.PaymentService;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.purchasing.RequisitionService;
import cn.erp.receiving.ReceivingQueries;
import cn.erp.reversal.ReversalService;
import cn.erp.salesfinance.ReceiptService;
import cn.erp.salesfinance.RefundApplicationService;
import cn.erp.salesfinance.SalesReturnService;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.shared.ApiException;
import cn.erp.stockcount.StockCountService;
import cn.erp.stocktransfer.StockTransferService;
import cn.erp.workflow.GenericWorkflowBusiness;

/** 附件与打印复用业务查询的数据范围和字段权限，类型只能来自服务端白名单。 */
@Service
public class DocumentAccess {
    private final JdbcTemplate jdbc;
    private final Map<String, BiFunction<CompanyAccess, Long, Map<String, Object>>> readers = new LinkedHashMap<>();
    private final GenericWorkflowBusiness genericWorkflow;

    public DocumentAccess(JdbcTemplate jdbc, SalesOrderQueries sales, ShipmentQueries shipments,
            ReceiptService receipts, SalesReturnService salesReturns, RefundApplicationService refunds,
            RequisitionService requisitions, PurchaseOrderQueries purchases, ReceivingQueries receiving,
            PaymentService payments, AccountTransferService transfers, OpeningService opening,
            StockCountService counts, StockTransferService stockTransfers, ReversalService reversals, InvoiceService invoices,
            GenericWorkflowBusiness genericWorkflow) {
        this.jdbc = jdbc;
        this.genericWorkflow = genericWorkflow;
        readers.put("sales_order", sales::get);
        readers.put("shipment", shipments::get);
        readers.put("receipt", receipts::get);
        readers.put("sales_return", salesReturns::get);
        readers.put("refund_request", refunds::get);
        readers.put("purchase_requisition", requisitions::get);
        readers.put("purchase_order", purchases::get);
        readers.put("goods_receipt", (scope, id) -> receiving.get(scope, false, id));
        readers.put("purchase_return", (scope, id) -> receiving.get(scope, true, id));
        readers.put("payment", payments::get);
        readers.put("account_transfer", transfers::get);
        readers.put("stock_adjustment", (scope, id) -> opening.get(scope, true, id));
        readers.put("opening_entry", (scope, id) -> opening.get(scope, false, id));
        readers.put("stock_count", counts::get);
        readers.put("stock_transfer", stockTransfers::get);
        readers.put("document_reversal", reversals::get);
        readers.put("invoice_registration", invoices::get);
        readers.put("wf_generic_request", genericWorkflow::viewAttachments);
    }

    public String permission(String type, String action) {
        validate(type);
        return (type.equals("document_reversal") ? "reversal" : type) + ":" + action;
    }

    public Set<String> supportedTypes() {
        return Set.copyOf(readers.keySet());
    }

    public String writePermission(String type) {
        return permission(type, type.equals("document_reversal") || type.equals("invoice_registration") ? "create" : "update");
    }

    public Map<String, Object> view(CompanyAccess scope, String type, long id) {
        require(scope, permission(type, "view"));
        if (id <= 0) {
            throw new ApiException(400, "VALIDATION_FAILED", "单据标识无效");
        }
        return readers.get(type).apply(scope, id);
    }

    public void writable(CompanyAccess scope, String type, long id) {
        if (type.equals("wf_generic_request")) {
            view(scope,type,id);
            genericWorkflow.modifyAttachments(scope,id);
            return;
        }
        require(scope, writePermission(type));
        view(scope, type, id);
    }

    /** 所有附件数量检查都串行锁定所属单据，避免不同幂等键同时突破上限。 */
    public void lock(CompanyAccess scope, String type, long id) {
        validate(type);
        var rows = jdbc.queryForList("SELECT id FROM " + type + " WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",
                scope.tenantId(), scope.companyId(), id);
        if (rows.isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "单据不存在或无权查看");
        }
        writable(scope, type, id);
    }

    public static void require(CompanyAccess scope, String permission) {
        if (!scope.hasPermission(permission)) {
            throw new ApiException(403, "FORBIDDEN", "没有执行此操作的权限");
        }
    }

    private void validate(String type) {
        if (!readers.containsKey(type)) {
            throw new ApiException(400, "UNSUPPORTED_DOCUMENT_TYPE", "不支持的业务单据类型");
        }
    }
}
