package cn.erp.wms;

import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.PutawayPort;
import cn.erp.shared.WarehouseScope;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 上架任务与来源单据的入库流水在同一事务写入。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class PutawayTaskWriter implements PutawayPort {
    private final JdbcTemplate jdbc;

    PutawayTaskWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Source(String table,String movement) {
        static Source of(String type) {
            return switch (type) {
                case "goods_receipt" -> new Source("goods_receipt","purchase_in");
                case "sales_return" -> new Source("sales_return","sales_return_in");
                default -> throw new IllegalArgumentException("该单据类型不生成上架任务");
            };
        }
    }

    private record ReceiptLine(long movement,long sourceLine,long sku,Long batch,long location,BigDecimal quantity) {}

    @Override
    public Long receivingLocation(WarehouseScope actor,long warehouse) {
        return jdbc.queryForList("""
                SELECT l.id FROM warehouse w
                JOIN wms_location l ON l.tenant_id=w.tenant_id AND l.company_id=w.company_id
                    AND l.warehouse_id=w.id AND l.id=w.default_receiving_location_id AND l.status='active'
                JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                    AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.type='receiving' AND z.status='active'
                WHERE w.tenant_id=? AND w.company_id=? AND w.id=? AND w.wms_task_mode IN ('optional','required')
                  AND w.location_managed=1 AND w.status='active' FOR SHARE
                """,Long.class,actor.tenantId(),actor.companyId(),warehouse).stream().findFirst().orElse(null);
    }

    @Override
    public void createFromReceipt(WarehouseScope actor,long warehouse,String sourceType,long sourceId) {
        var source = Source.of(sourceType);
        var enabled = jdbc.queryForList("""
                SELECT wms_task_mode FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?
                AND location_managed=1 FOR SHARE
                """,String.class,actor.tenantId(),actor.companyId(),warehouse).stream().findFirst();
        if (enabled.isEmpty() || "off".equals(enabled.get())) return;
        var lines = eligibleLines(actor,warehouse,source,sourceId);
        if (lines.isEmpty()) return;
        var origin = jdbc.queryForMap("""
                SELECT owner_department_id,owner_user_id FROM %s WHERE tenant_id=? AND company_id=?
                  AND warehouse_id=? AND id=? AND status='posted'
                """.formatted(source.table()),actor.tenantId(),actor.companyId(),warehouse,sourceId);
        long task = Ids.next();
        jdbc.update("""
                INSERT INTO wms_task(id,tenant_id,company_id,warehouse_id,no,type,source_type,source_id,
                    owner_department_id,owner_user_id) VALUES(?,?,?,?,?,'putaway',?,?,?,?)
                """,task,actor.tenantId(),actor.companyId(),warehouse,"PA-"+task,sourceType,sourceId,
                origin.get("owner_department_id"),origin.get("owner_user_id"));
        var values = lines.stream().map(line -> new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),task,line.sku(),
                line.batch(),line.location(),line.sourceLine(),line.movement(),line.quantity()}).toList();
        jdbc.batchUpdate("""
                INSERT INTO wms_task_line(id,tenant_id,company_id,task_id,sku_id,batch_id,from_location_id,
                    source_line_id,receipt_movement_id,plan_qty) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,values);
    }

    private List<ReceiptLine> eligibleLines(WarehouseScope actor,long warehouse,Source source,long sourceId) {
        return jdbc.query("""
                SELECT m.id,m.source_line_id,m.sku_id,NULLIF(m.lot_key,'') batch_id,l.id location_id,m.on_hand_delta
                FROM inventory_movement m
                JOIN wms_location l ON l.tenant_id=m.tenant_id AND l.company_id=m.company_id
                    AND l.warehouse_id=m.warehouse_id AND l.id=CAST(m.location_key AS UNSIGNED) AND l.status='active'
                JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                    AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active' AND z.type='receiving'
                WHERE m.tenant_id=? AND m.company_id=? AND m.warehouse_id=? AND m.source_type=? AND m.source_id=?
                    AND m.movement_type=? AND m.posting_action='post' AND m.on_hand_delta>0 AND m.blocked_delta=0
                ORDER BY m.id
                """,(row,i) -> new ReceiptLine(row.getLong("id"),row.getLong("source_line_id"),row.getLong("sku_id"),
                row.getObject("batch_id")==null ? null : row.getLong("batch_id"),row.getLong("location_id"),
                row.getBigDecimal("on_hand_delta")),actor.tenantId(),actor.companyId(),warehouse,
                source.table()+"_line",sourceId,source.movement());
    }

    @Override
    public void requireReversible(WarehouseScope actor,String sourceType,long sourceId) {
        if (!isReceipt(sourceType)) return;
        var moved = jdbc.queryForList("""
                SELECT l.id FROM wms_task t JOIN wms_task_line l
                ON l.tenant_id=t.tenant_id AND l.company_id=t.company_id AND l.task_id=t.id
                WHERE t.tenant_id=? AND t.company_id=? AND t.source_type=? AND t.source_id=? AND l.done_qty>0
                LIMIT 1 FOR SHARE
                """,Long.class,actor.tenantId(),actor.companyId(),sourceType,sourceId);
        if (!moved.isEmpty()) throw new ApiException(422,"WMS_PUTAWAY_ALREADY_MOVED","原单已有上架移库记录，不能直接冲销");
    }

    @Override
    public void cancelUntouched(WarehouseScope actor,String sourceType,long sourceId) {
        if (!isReceipt(sourceType)) return;
        jdbc.update("""
                UPDATE wms_task SET status='cancelled',version=version+1
                WHERE tenant_id=? AND company_id=? AND source_type=? AND source_id=? AND status IN ('created','assigned')
                """,actor.tenantId(),actor.companyId(),sourceType,sourceId);
    }

    private boolean isReceipt(String sourceType) {
        return "goods_receipt".equals(sourceType) || "sales_return".equals(sourceType);
    }
}
