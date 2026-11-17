package cn.erp.portal;

import cn.erp.documents.ObjectFiles;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 所有公开读取方法只接受已经过门户认证的作用域。 */
@Service
@Transactional(readOnly = true)
public class PortalPurchases {
    private static final String ORDERS = """
            FROM sales_order WHERE tenant_id=? AND company_id=? AND customer_id=? AND is_deleted=0
              AND status IN ('approved','change_pending','closed','cancelled') AND approved_at IS NOT NULL
            """;
    private static final String ORDER_FIELDS = """
            SELECT id,order_no document_no,status,fulfillment_status,settlement_status,business_date,delivery_date,total_amount,currency
            """;
    private static final String SHIPMENTS = """
            FROM shipment WHERE tenant_id=? AND company_id=? AND customer_id=?
              AND status IN ('posted','signed','signed_with_diff','reversed')
            """;
    private static final String SHIPMENT_FIELDS = """
            SELECT id,shipment_no document_no,status,business_date,sign_date,delivery_method,logistics_no
            """;
    private static final String INVOICES = """
            SELECT * FROM (
              SELECT id,invoice_no,red_invoice_no,issued_at,invoice_kind,status,amount,tax_amount
              FROM einv_issue_request WHERE tenant_id=? AND company_id=? AND partner_id=? AND invoice_no IS NOT NULL
                AND status IN ('issued','red_submitted','red_issued')
              UNION ALL
              SELECT id,invoice_no,NULL,CAST(invoice_date AS DATETIME),'registration','issued',amount,tax_amount
              FROM invoice_registration WHERE tenant_id=? AND company_id=? AND partner_id=? AND invoice_type='sales'
                AND status='registered' AND einvoice_request_id IS NULL
            ) records WHERE 1=1
            """;
    private final JdbcTemplate jdbc;
    private final ObjectFiles files;

    PortalPurchases(JdbcTemplate jdbc, ObjectFiles files) {
        this.jdbc=jdbc;
        this.files=files;
    }

    public record Order(String id,String documentNo,String status,String fulfillmentStatus,String settlementStatus,
                        String businessDate,String deliveryDate,String totalAmount,String currency) {}
    public record Shipment(String id,String documentNo,String status,String businessDate,String signDate,
                           String deliveryMethod,String logisticsNo) {}
    public record Invoice(String id,String invoiceNo,String redInvoiceNo,String issuedAt,String invoiceKind,String status,
                          String amount,String taxAmount) {}
    public record Download(String filename,byte[] bytes) {}

    public Map<String,Object> dashboard(PortalActor actor) {
        var parameters=scope(actor);
        Long awaiting=jdbc.queryForObject("SELECT COUNT(*) "+SHIPMENTS+" AND status='posted'",Long.class,parameters);
        Long orders=jdbc.queryForObject("SELECT COUNT(*) "+ORDERS+" AND status IN('approved','change_pending') AND fulfillment_status<>'full'",Long.class,parameters);
        var unpaid=jdbc.queryForObject("""
                SELECT COALESCE(SUM(IF(direction='D',open_amount,-open_amount)),0) FROM partner_open_item
                WHERE tenant_id=? AND company_id=? AND partner_id=? AND item_type='receivable' AND currency='CNY'
                """,java.math.BigDecimal.class,parameters);
        return Map.of("pendingReceiptCount",awaiting,"openOrderCount",orders,"unpaidAmount",unpaid.toPlainString(),
                "currency","CNY","recentShipments",shipments(actor,null,5).stream().limit(5).toList());
    }

    public List<Order> orders(PortalActor actor,Long cursor,int limit) {
        return jdbc.query(ORDER_FIELDS+ORDERS+" AND id<? ORDER BY id DESC LIMIT ?",this::order,
                actor.tenantId(),actor.companyId(),actor.partnerId(),PortalValues.cursor(cursor,limit),limit+1);
    }

    public Map<String,Object> order(PortalActor actor,long id) {
        var order=jdbc.query(ORDER_FIELDS+ORDERS+" AND id=?",this::order,actor.tenantId(),actor.companyId(),actor.partnerId(),id)
                .stream().findFirst().orElseThrow(()->PortalValues.missing("订单"));
        var lines=jdbc.queryForList("""
                SELECT ol.line_no,k.name sku_name,u.name unit_name,ol.qty,ol.unit_price,ol.receivable_net amount,
                  ol.shipped_base_qty,ol.signed_base_qty
                FROM sales_order_line ol JOIN sku k ON k.tenant_id=ol.tenant_id AND k.id=ol.sku_id
                JOIN unit u ON u.tenant_id=ol.tenant_id AND u.id=ol.unit_id
                WHERE ol.tenant_id=? AND ol.company_id=? AND ol.order_id=? AND ol.is_deleted=0 ORDER BY ol.line_no
                """,actor.tenantId(),actor.companyId(),id).stream().map(cn.erp.salesfinance.FinanceSupport::row).toList();
        return Map.of("order",order,"lines",lines);
    }

    public List<Shipment> shipments(PortalActor actor,Long cursor,int limit) {
        return jdbc.query(SHIPMENT_FIELDS+SHIPMENTS+" AND id<? ORDER BY id DESC LIMIT ?",this::shipment,
                actor.tenantId(),actor.companyId(),actor.partnerId(),PortalValues.cursor(cursor,limit),limit+1);
    }

    public Map<String,Object> shipment(PortalActor actor,long id) {
        var shipment=jdbc.query(SHIPMENT_FIELDS+SHIPMENTS+" AND id=?",this::shipment,actor.tenantId(),actor.companyId(),actor.partnerId(),id)
                .stream().findFirst().orElseThrow(()->PortalValues.missing("发货单"));
        var lines=jdbc.queryForList("""
                SELECT l.line_no,k.name sku_name,u.name unit_name,l.qty,l.signed_base_qty
                FROM shipment_line l JOIN sku k ON k.tenant_id=l.tenant_id AND k.id=l.sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.shipment_id=? ORDER BY l.line_no
                """,actor.tenantId(),actor.companyId(),id).stream().map(cn.erp.salesfinance.FinanceSupport::row).toList();
        return Map.of("shipment",shipment,"lines",lines);
    }

    public List<Invoice> invoices(PortalActor actor,Long cursor,int limit) {
        return jdbc.query(INVOICES+" AND id<? ORDER BY id DESC LIMIT ?",this::invoice,
                actor.tenantId(),actor.companyId(),actor.partnerId(),actor.tenantId(),actor.companyId(),actor.partnerId(),
                PortalValues.cursor(cursor,limit),limit+1);
    }

    public Invoice invoice(PortalActor actor,long id) {
        return jdbc.query(INVOICES+" AND id=?",this::invoice,actor.tenantId(),actor.companyId(),actor.partnerId(),
                actor.tenantId(),actor.companyId(),actor.partnerId(),id)
                .stream().findFirst().orElseThrow(()->PortalValues.missing("发票"));
    }

    Download file(PortalActor actor,long id,String variant,String format) {
        if(!List.of("issue","red").contains(variant) || !List.of("pdf","ofd","xml").contains(format))
            throw PortalValues.invalid("发票下载选项无效");
        var invoice=invoice(actor,id);
        if("registration".equals(invoice.invoiceKind())) throw PortalValues.missing("发票文件");
        if("red".equals(variant) && !"red_issued".equals(invoice.status())) throw PortalValues.missing("发票文件");
        var select=jdbc.queryForList("""
                SELECT a.storage_key FROM einv_file f JOIN attachment a
                  ON a.id=f.attachment_id AND a.tenant_id=f.tenant_id AND a.company_id=f.company_id
                  AND a.owner_type='einv_issue_request' AND a.owner_id=f.request_id AND a.status='active'
                WHERE f.tenant_id=? AND f.company_id=? AND f.request_id=? AND f.variant=? AND f.file_format=?
                """,actor.tenantId(),actor.companyId(),id,variant,format);
        if(select.isEmpty()) throw PortalValues.missing("发票文件");
        String number="red".equals(variant)?invoice.redInvoiceNo():invoice.invoiceNo();
        if(number==null || !number.matches("[A-Za-z0-9_-]{1,64}")) number="invoice-"+invoice.id();
        return new Download(number+"."+format,files.read(select.getFirst().get("storage_key").toString()));
    }

    private Object[] scope(PortalActor actor) {return new Object[]{actor.tenantId(),actor.companyId(),actor.partnerId()};}

    private Order order(java.sql.ResultSet row,int count) throws java.sql.SQLException {
        return new Order(row.getString("id"),row.getString("document_no"),row.getString("status"),row.getString("fulfillment_status"),
                row.getString("settlement_status"),row.getString("business_date"),row.getString("delivery_date"),row.getString("total_amount"),row.getString("currency"));
    }

    private Shipment shipment(java.sql.ResultSet row,int count) throws java.sql.SQLException {
        return new Shipment(row.getString("id"),row.getString("document_no"),row.getString("status"),row.getString("business_date"),
                row.getString("sign_date"),row.getString("delivery_method"),row.getString("logistics_no"));
    }

    private Invoice invoice(java.sql.ResultSet row,int count) throws java.sql.SQLException {
        return new Invoice(row.getString("id"),row.getString("invoice_no"),row.getString("red_invoice_no"),row.getString("issued_at"),
                row.getString("invoice_kind"),row.getString("status"),row.getString("amount"),row.getString("tax_amount"));
    }
}
