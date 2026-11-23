package cn.erp.alert;

import cn.erp.ar.CreditService;
import cn.erp.identity.CompanyAccess;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class AlertProbes {
    private static final int JOB_ITEM_FAILURE_THRESHOLD = 3;
    private final NamedParameterJdbcTemplate jdbc;
    private final CreditService credit;

    AlertProbes(NamedParameterJdbcTemplate jdbc,CreditService credit) {this.jdbc=jdbc;this.credit=credit;}

    List<AlertCandidate> detect(long tenant,long company,AlertRules.Rule rule,Instant instant) {
        var today=LocalDate.ofInstant(instant,AlertRules.ZONE);
        var params=new LinkedHashMap<String,Object>();
        params.put("t",tenant);params.put("c",company);params.put("today",today);params.put("n",rule.threshold());
        return switch (rule.kind()) {
            case CREDIT_EXCEEDED -> credit(tenant,company,rule.threshold(),params);
            case RECEIVABLE_OVERDUE -> rows("partner_open_item",OVERDUE,params);
            case PAYABLE_DUE -> rows("partner_open_item",PAYABLE_DUE,params);
            case NEGATIVE_MARGIN -> rows("shipment_line",MARGIN,params);
            case STOCK_BELOW_SAFETY -> rows("stock_balance",STOCK_SAFETY,params);
            case STOCK_SLOW_MOVING -> rows("stock_balance",STOCK_SLOW,params);
            case BATCH_NEAR_EXPIRY -> rows("inv_batch",BATCH.formatted("b.status='normal' AND b.expiry_date BETWEEN :today AND DATE_ADD(:today,INTERVAL COALESCE(:n,s.near_expiry_days) DAY)"),params);
            case BATCH_EXPIRED -> rows("inv_batch",BATCH.formatted("b.expiry_date<:today"),params);
            case QC_FAILED -> rows("qc_inspection",QUALITY,params);
            case PRICE_DEVIATION -> rows("purchase_order_line",PRICE,params);
            case FREQUENT_REVERSAL -> rows("reversal_user",REVERSAL,params);
            case DUPLICATE_PAYMENT -> rows("payment",PAYMENT,params);
            case WORKFLOW_OVERDUE -> rows("wf_task_ext",WORKFLOW,params);
            case JOB_FAILED -> failed(params);
            case COLLECTION_DUE -> rows("ar_collection_plan",COLLECTION,params);
        };
    }

    private List<AlertCandidate> rows(String type,String sql,LinkedHashMap<String,Object> params) {
        return jdbc.query(sql,params,(r,n)->new AlertCandidate(type,r.getLong("id"),r.getString("event_key"),
                r.getObject("warehouse_id",Long.class),r.getLong("owner_department_id"),r.getLong("owner_user_id"),
                r.getString("detail"),r.getString("target_url")));
    }

    private List<AlertCandidate> credit(long tenant,long company,BigDecimal threshold,LinkedHashMap<String,Object> params) {
        var actor=new CompanyAccess(tenant,0,company,true,Set.of(),Set.of("credit_limit:view","cost:view"));
        var customers=jdbc.queryForList("""
                SELECT a.id,a.partner_id,b.name,COALESCE(cp.owner_user_id,0) owner_user_id,COALESCE(cp.owner_department_id,0) owner_department_id
                FROM ar_credit_limit a JOIN business_partner b ON b.tenant_id=a.tenant_id AND b.id=a.partner_id
                LEFT JOIN crm_customer_profile cp ON cp.tenant_id=a.tenant_id AND cp.company_id=a.company_id AND cp.partner_id=a.partner_id
                WHERE a.tenant_id=:t AND a.company_id=:c AND :today>=a.valid_from AND (a.valid_to IS NULL OR a.valid_to>=:today)
                ORDER BY a.id
                """,params);
        var result=new ArrayList<AlertCandidate>();
        for (var row : customers) {
            long id=((Number)row.get("id")).longValue();
            long customer=((Number)row.get("partner_id")).longValue();
            var exposure=credit.exposure(actor,customer);
            if (exposure.get("creditExposure")==null) continue;
            var excess=new BigDecimal(exposure.get("creditExposure").toString()).subtract(new BigDecimal(exposure.get("creditLimit").toString()));
            if (excess.compareTo(threshold)>0) result.add(new AlertCandidate("ar_credit_limit",id,Long.toString(id),null,
                    ((Number)row.get("owner_department_id")).longValue(),((Number)row.get("owner_user_id")).longValue(),
                    row.get("name")+" 当前信用占用超额 "+excess.stripTrailingZeros().toPlainString()+" 元。","/ar/credit"));
        }
        return result;
    }

    private List<AlertCandidate> failed(LinkedHashMap<String,Object> params) {
        var result=new ArrayList<>(rows("scheduled_job",JOB,params));
        params.put("failureThreshold",JOB_ITEM_FAILURE_THRESHOLD);
        result.addAll(rows("scheduled_job",JOB_ITEM,params));
        for (String table : List.of("import_job","export_job")) result.addAll(rows(table,FILE_JOB.formatted(table),params));
        return result;
    }

    private static final String OVERDUE="""
            SELECT o.id,CAST(o.id AS CHAR) event_key,NULL warehouse_id,
               COALESCE(h.owner_department_id,p.owner_department_id,0) owner_department_id,
               COALESCE(h.owner_user_id,p.owner_user_id,0) owner_user_id,
               CONCAT(LEFT(b.name,80),' 已逾期 ',DATEDIFF(:today,a.due_date),' 天，未清 ',o.open_amount,' 元。') detail,
               CONCAT('/reports/receivable-aging?partnerId=',o.partner_id) target_url
            FROM partner_open_item o JOIN receivable a ON a.tenant_id=o.tenant_id AND a.company_id=o.company_id AND a.id=o.source_id
            JOIN business_partner b ON b.tenant_id=o.tenant_id AND b.id=o.partner_id
            LEFT JOIN shipment h ON h.tenant_id=a.tenant_id AND h.company_id=a.company_id AND h.id=a.source_id
              AND a.source_type IN('shipment_line','sales_order_fee')
            LEFT JOIN crm_customer_profile p ON p.tenant_id=o.tenant_id AND p.company_id=o.company_id AND p.partner_id=o.partner_id
            WHERE o.tenant_id=:t AND o.company_id=:c AND o.item_type='receivable' AND o.direction='D' AND o.open_amount>0
              AND a.due_date<DATE_SUB(:today,INTERVAL :n DAY) ORDER BY o.id
            """;
    private static final String PAYABLE_DUE="""
            SELECT o.id,CAST(o.id AS CHAR) event_key,NULL warehouse_id,
                COALESCE(h.owner_department_id,e.owner_department_id,0) owner_department_id,
                COALESCE(h.owner_user_id,e.owner_user_id,0) owner_user_id,
                CONCAT(LEFT(b.name,80),' 应付将于 ',a.due_date,' 到期，未清 ',o.open_amount,' 元。') detail,
                CONCAT('/reports/payable-aging?partnerId=',o.partner_id) target_url
            FROM partner_open_item o JOIN payable a ON a.tenant_id=o.tenant_id AND a.company_id=o.company_id AND a.id=o.source_id
            JOIN business_partner b ON b.tenant_id=o.tenant_id AND b.id=o.partner_id
            LEFT JOIN goods_receipt h ON h.tenant_id=a.tenant_id AND h.company_id=a.company_id AND h.id=a.source_id
                AND a.source_type IN ('goods_receipt_line','purchase_order_fee')
            LEFT JOIN opening_entry e ON e.tenant_id=a.tenant_id AND e.company_id=a.company_id AND e.id=a.source_id AND a.source_type='opening_entry'
            WHERE o.tenant_id=:t AND o.company_id=:c AND o.item_type='payable' AND o.direction='C' AND o.open_amount>0
                AND a.due_date BETWEEN :today AND DATE_ADD(:today,INTERVAL :n DAY) ORDER BY o.id
            """;
    private static final String MARGIN="""
            SELECT l.id,CAST(l.id AS CHAR) event_key,h.warehouse_id,h.owner_department_id,h.owner_user_id,
               CONCAT('发货单 ',h.shipment_no,' 第 ',l.line_no,' 行毛利为 ',l.untaxed_amount-l.signed_cost_amount,' 元。') detail,
               CONCAT('/shipments/',h.id) target_url
            FROM shipment_line l JOIN shipment h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.shipment_id
            WHERE l.tenant_id=:t AND l.company_id=:c AND h.status IN ('signed','signed_with_diff') AND l.signed_base_qty>0
              AND h.sign_date>=DATE_SUB(:today,INTERVAL 30 DAY) AND l.untaxed_amount-l.signed_cost_amount<-:n ORDER BY l.id
            """;
    private static final String STOCK_SAFETY="""
            SELECT s.id,CONCAT(w.id,':',s.id) event_key,w.id warehouse_id,0 owner_department_id,0 owner_user_id,
                CONCAT(w.name,' 的商品 ',LEFT(s.name,80),' 可用 ',COALESCE(b.on_hand_qty-b.blocked_qty-b.reserved_qty,0),'；安全库存 ',COALESCE(v.quantity,s.safety_stock),'。') detail,
                CONCAT('/inventory-balances?warehouseId=',w.id,'&skuId=',s.id) target_url
            FROM warehouse w JOIN sku s ON s.tenant_id=w.tenant_id AND s.status='active'
            LEFT JOIN alert_sku_safety_stock v ON v.tenant_id=w.tenant_id AND v.company_id=w.company_id AND v.sku_id=s.id
            LEFT JOIN inventory_balance b ON b.tenant_id=w.tenant_id AND b.company_id=w.company_id AND b.warehouse_id=w.id AND b.sku_id=s.id
              AND b.lot_key='' AND b.location_key=''
            WHERE w.tenant_id=:t AND w.company_id=:c AND w.status='active' AND COALESCE(v.quantity,s.safety_stock)>0
              AND COALESCE(b.on_hand_qty-b.blocked_qty-b.reserved_qty,0)<COALESCE(v.quantity,s.safety_stock)
            ORDER BY s.id,w.id
            """;
    private static final String STOCK_SLOW="""
            SELECT b.id,CONCAT(b.warehouse_id,':',b.sku_id) event_key,b.warehouse_id,0 owner_department_id,0 owner_user_id,
                CONCAT(w.name,' 的商品 ',LEFT(s.name,80),' 长期无出库，当前在库 ',b.on_hand_qty,'。') detail,
                CONCAT('/inventory-balances?warehouseId=',b.warehouse_id,'&skuId=',b.sku_id) target_url
            FROM inventory_balance b JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
            JOIN warehouse w ON w.tenant_id=b.tenant_id AND w.company_id=b.company_id AND w.id=b.warehouse_id
            WHERE b.tenant_id=:t AND b.company_id=:c AND b.lot_key='' AND b.location_key='' AND b.on_hand_qty>0
              AND b.created_at<DATE_SUB(:today,INTERVAL :n DAY) AND NOT EXISTS (
                SELECT 1 FROM inventory_movement m WHERE m.tenant_id=b.tenant_id AND m.company_id=b.company_id
                  AND m.sku_id=b.sku_id AND m.warehouse_id=b.warehouse_id AND m.business_date>=DATE_SUB(:today,INTERVAL :n DAY)
                  AND m.on_hand_delta<0 AND m.posting_action='post' AND m.movement_type NOT IN ('location_move_out','transfer_out'))
              AND EXISTS(SELECT 1 FROM inventory_movement m WHERE m.tenant_id=b.tenant_id AND m.company_id=b.company_id
                  AND m.sku_id=b.sku_id AND m.warehouse_id=b.warehouse_id AND m.business_date<DATE_SUB(:today,INTERVAL :n DAY)
                  AND m.on_hand_delta>0) ORDER BY b.id
            """;
    private static final String BATCH="""
            SELECT DISTINCT b.id,CONCAT(b.id,':',x.warehouse_id) event_key,x.warehouse_id,0 owner_department_id,0 owner_user_id,
                CONCAT(w.name,' 的批次 ',b.batch_no,'，到期日 ',b.expiry_date,'。') detail,
                CONCAT('/inventory-trace?warehouseId=',x.warehouse_id,'&skuId=',x.sku_id,'&batchId=',b.id) target_url
            FROM inv_batch b JOIN sku s ON s.tenant_id=b.tenant_id AND s.id=b.sku_id
            JOIN inventory_balance x ON x.tenant_id=b.tenant_id AND x.company_id=b.company_id AND x.sku_id=b.sku_id AND x.lot_key=CAST(b.id AS CHAR)
            JOIN warehouse w ON w.tenant_id=x.tenant_id AND w.company_id=x.company_id AND w.id=x.warehouse_id
            WHERE b.tenant_id=:t AND b.company_id=:c AND x.on_hand_qty>0 AND %s ORDER BY b.id,x.warehouse_id
            """;
    private static final String QUALITY="""
            SELECT q.id,CONCAT(q.id,':',q.round_no) event_key,q.warehouse_id,q.owner_department_id,q.owner_user_id,
                CONCAT('来料检验 ',q.inspection_no,' 有未处理的不合格数量。') detail,CONCAT('/quality?inspectionId=',q.id) target_url
            FROM qc_inspection q WHERE q.tenant_id=:t AND q.company_id=:c AND q.status='approved' AND q.unqualified_qty>(
                SELECT COALESCE(SUM(d.qty),0) FROM qc_disposition d
                LEFT JOIN purchase_return r ON r.tenant_id=d.tenant_id AND r.company_id=d.company_id AND r.id=d.target_id AND d.target_type='purchase_return'
                LEFT JOIN stock_adjustment s ON s.tenant_id=d.tenant_id AND s.company_id=d.company_id AND s.id=d.target_id AND d.target_type='stock_adjustment'
                WHERE d.tenant_id=q.tenant_id AND d.company_id=q.company_id AND d.inspection_id=q.id AND d.round_no=q.round_no
                  AND (d.action='reinspect' OR COALESCE(r.status,s.status) NOT IN ('voided','reversed')))
            ORDER BY q.id
            """;
    private static final String PRICE="""
            SELECT l.id,CAST(l.id AS CHAR) event_key,l.warehouse_id,h.owner_department_id,h.owner_user_id,
                CONCAT('采购单 ',h.order_no,' 第 ',l.line_no,' 行较近三次基准单价偏离 ',ROUND(ABS(l.unit_price/l.unit_rate-a.average)/a.average*100,2),'%%。') detail,
                CONCAT('/purchase-orders/',h.id) target_url
            FROM purchase_order_line l JOIN purchase_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
            JOIN LATERAL (
                SELECT AVG(hist.price) average,COUNT(*) samples FROM (
                    SELECT p.unit_price/p.unit_rate price FROM purchase_order_line p JOIN purchase_order prior
                      ON prior.tenant_id=p.tenant_id AND prior.company_id=p.company_id AND prior.id=p.order_id
                    WHERE p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.sku_id=l.sku_id
                      AND p.is_deleted=0 AND prior.supplier_id=h.supplier_id AND prior.is_deleted=0 AND prior.price_tax_inclusive=h.price_tax_inclusive
                      AND prior.status IN ('approved','change_pending','closed')
                      AND (prior.approved_at<h.approved_at OR prior.approved_at=h.approved_at AND prior.id<h.id)
                    ORDER BY prior.approved_at DESC,prior.id DESC,p.id DESC LIMIT 3
                ) hist
            ) a ON a.samples=3 AND a.average>0
            WHERE l.tenant_id=:t AND l.company_id=:c AND l.is_deleted=0 AND h.is_deleted=0
              AND h.status IN ('approved','change_pending','closed') AND h.business_date>=DATE_SUB(:today,INTERVAL 30 DAY)
              AND ABS(l.unit_price/l.unit_rate-a.average)*100>a.average*:n ORDER BY l.id
            """.replace("%%","%");
    private static final String REVERSAL="""
            SELECT r.created_by id,CAST(r.created_by AS CHAR) event_key,NULL warehouse_id,0 owner_department_id,r.created_by owner_user_id,
                CONCAT(LEFT(u.display_name,80),' 近七天过账冲销 ',COUNT(*),' 次。') detail,'/document-reversals' target_url
            FROM document_reversal r JOIN sys_user u ON u.tenant_id=r.tenant_id AND u.id=r.created_by
            WHERE r.tenant_id=:t AND r.company_id=:c AND r.status='posted' AND r.posted_at>=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 7 DAY)
            GROUP BY r.created_by,u.display_name HAVING COUNT(*)>:n ORDER BY r.created_by
            """;
    private static final String PAYMENT="""
            SELECT p.id,CAST(p.id AS CHAR) event_key,NULL warehouse_id,p.owner_department_id,p.owner_user_id,
                CONCAT('付款单 ',p.payment_no,' 在近 24 小时内存在同供应商、同币种、同金额付款。') detail,
                CONCAT('/payments/',p.id) target_url
            FROM payment p WHERE p.tenant_id=:t AND p.company_id=:c AND p.payment_type='supplier' AND p.status='confirmed'
              AND p.confirmed_at>=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 DAY) AND EXISTS(
                SELECT 1 FROM payment old WHERE old.tenant_id=p.tenant_id AND old.company_id=p.company_id
                  AND old.payment_type='supplier' AND old.status='confirmed' AND old.partner_id=p.partner_id
                  AND old.currency=p.currency AND old.amount=p.amount AND old.confirmed_at>=DATE_SUB(p.confirmed_at,INTERVAL 1 DAY)
                  AND (old.confirmed_at<p.confirmed_at OR old.confirmed_at=p.confirmed_at AND old.id<p.id)) ORDER BY p.id
            """;
    private static final String WORKFLOW="""
            SELECT t.id,CAST(t.id AS CHAR) event_key,NULL warehouse_id,w.starter_dept_id owner_department_id,t.assignee_id owner_user_id,
                CONCAT('单据 ',w.business_no,' 的审批节点 ',t.node_name,' 已超时。') detail,
                CONCAT('/workflow/instances/',w.id) target_url
            FROM wf_task_ext t JOIN wf_instance w ON w.tenant_id=t.tenant_id AND w.company_id=t.company_id AND w.id=t.instance_id
            WHERE t.tenant_id=:t AND t.company_id=:c AND t.status='pending' AND w.status='running'
              AND t.due_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL :n HOUR) ORDER BY t.id
            """;
    private static final String JOB="""
            SELECT j.id,d.code event_key,NULL warehouse_id,0 owner_department_id,COALESCE(j.operator_id,0) owner_user_id,
                CONCAT('定时任务 ',d.name,' 最近一次运行失败。') detail,'/sys/jobs' target_url
            FROM job_definition d JOIN job_run_log j ON j.id=(SELECT MAX(recent.id) FROM job_run_log recent
                WHERE recent.tenant_id=d.tenant_id AND recent.job_code=d.code AND recent.status IN ('success','failed','partial_failed'))
            WHERE d.tenant_id=:t AND d.last_result='failed' AND d.code<>'alert.scan' AND j.tenant_id=d.tenant_id AND j.status='failed'
            ORDER BY j.id
            """;
    private static final String JOB_ITEM="""
            SELECT f.id,CONCAT('item:',f.id) event_key,NULL warehouse_id,0 owner_department_id,0 owner_user_id,
                CONCAT('定时任务 ',d.name,' 有一项连续失败 ',f.failure_count,' 次（',f.error_code,'）。') detail,
                CASE WHEN f.job_code IN ('workflow.timeout','workflow.delay')
                     THEN CONCAT('/workflow/instances/',f.target_id) ELSE '/sys/jobs' END target_url
            FROM job_item_failure f JOIN job_definition d ON d.tenant_id=f.tenant_id AND d.code=f.job_code
            WHERE f.tenant_id=:t AND f.company_id=:c AND f.failure_count>=:failureThreshold AND (
                f.job_code='crm.pool_reclaim' AND EXISTS(
                    SELECT 1 FROM crm_pool_rule r WHERE r.tenant_id=f.tenant_id AND r.company_id=f.company_id AND r.enabled=1)
                OR f.job_code='wms.replenishment' AND EXISTS(
                    SELECT 1 FROM wms_location_rule r JOIN warehouse w
                      ON w.tenant_id=r.tenant_id AND w.company_id=r.company_id AND w.id=r.warehouse_id
                    WHERE r.tenant_id=f.tenant_id AND r.company_id=f.company_id AND r.warehouse_id=f.target_id
                      AND r.min_qty IS NOT NULL AND w.status='active' AND w.wms_task_mode<>'off')
                OR f.job_code='workflow.timeout' AND EXISTS(
                    SELECT 1 FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.company_id=t.company_id AND i.id=t.instance_id
                    WHERE t.tenant_id=f.tenant_id AND t.company_id=f.company_id AND t.id=CAST(f.item_key AS UNSIGNED)
                      AND t.instance_id=f.target_id AND t.status='pending' AND i.status='running')
                OR f.job_code='workflow.delay' AND EXISTS(
                    SELECT 1 FROM wf_delay t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.company_id=t.company_id AND i.id=t.instance_id
                    WHERE t.tenant_id=f.tenant_id AND t.company_id=f.company_id AND BINARY t.execution_id=BINARY f.item_key
                      AND t.instance_id=f.target_id AND i.status='running'))
            ORDER BY f.id
            """;
    private static final String FILE_JOB="""
            SELECT j.id,CAST(j.id AS CHAR) event_key,NULL warehouse_id,0 owner_department_id,j.created_by owner_user_id,
                '文件后台任务失败，请查看执行结果。' detail,'/file-jobs' target_url
            FROM %s j WHERE j.tenant_id=:t AND j.company_id=:c AND j.status='failed'
               AND j.finished_at>=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 7 DAY) ORDER BY j.id
            """;
    private static final String COLLECTION="""
            SELECT p.id,CAST(p.id AS CHAR) event_key,NULL warehouse_id,0 owner_department_id,p.owner_id owner_user_id,
                CONCAT('催收计划日期 ',p.plan_date,'，请核对未清余额并登记跟进。') detail,
                CONCAT('/ar/collections?planId=',p.id) target_url
            FROM ar_collection_plan p JOIN partner_open_item o ON o.tenant_id=p.tenant_id AND o.company_id=p.company_id AND o.id=p.open_item_id
            WHERE p.tenant_id=:t AND p.company_id=:c AND p.status='open' AND o.open_amount>0
              AND p.plan_date<=DATE_ADD(:today,INTERVAL 3 DAY) ORDER BY p.id
            """;
}
