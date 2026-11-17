package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.ShipmentLocationMoveService;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.ShipmentWorkPort;
import cn.erp.shared.WarehouseScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class ShipmentWork implements ShipmentWorkPort {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final ShipmentLocationMoveService placement;
    private final FinanceSupport finance;

    ShipmentWork(JdbcTemplate jdbc,InventoryService inventory,ShipmentLocationMoveService placement,FinanceSupport finance) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.placement = placement;
        this.finance = finance;
    }

    @Override
    public void submitted(WarehouseScope actor,long shipment) {
        var source = jdbc.queryForMap("""
                SELECT s.warehouse_id,s.owner_user_id,s.owner_department_id,w.wms_task_mode,w.location_managed,
                       l.id target_id
                FROM shipment s JOIN warehouse w ON w.tenant_id=s.tenant_id AND w.company_id=s.company_id AND w.id=s.warehouse_id
                LEFT JOIN wms_location l ON l.tenant_id=w.tenant_id AND l.company_id=w.company_id AND l.warehouse_id=w.id
                    AND l.id=w.default_shipping_location_id AND l.status='active'
                    AND EXISTS (SELECT 1 FROM wms_zone z WHERE z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                        AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active' AND z.type='shipping')
                WHERE s.tenant_id=? AND s.company_id=? AND s.id=?
                """,actor.tenantId(),actor.companyId(),shipment);
        if ("off".equals(source.get("wms_task_mode"))) return;
        boolean required = "required".equals(source.get("wms_task_mode"));
        if (source.get("target_id")==null || !Boolean.TRUE.equals(source.get("location_managed"))) {
            if (required) throw new ApiException(422,"WMS_SHIPPING_LOCATION_REQUIRED","请配置当前仓库启用的发货区默认库位");
            return;
        }
        insertTask(actor,shipment,source,required);
    }

    private void insertTask(WarehouseScope actor,long shipment,Map<String,Object> source,boolean required) {
        long id = Ids.next();
        jdbc.update("""
                INSERT INTO wms_task(id,tenant_id,company_id,warehouse_id,no,type,source_type,source_id,
                    owner_department_id,owner_user_id,required_at_submit)
                VALUES(?,?,?,?,?,'pick','shipment',?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),source.get("warehouse_id"),"PK-"+id,shipment,
                source.get("owner_department_id"),source.get("owner_user_id"),required);
        var allocations = jdbc.queryForList("""
                SELECT p.shipment_line_id,p.sku_id,p.batch_id,p.location_key,p.qty
                FROM shipment_line s JOIN inv_shipment_pick p ON p.tenant_id=s.tenant_id AND p.company_id=s.company_id
                    AND p.shipment_line_id=s.id
                WHERE s.tenant_id=? AND s.company_id=? AND s.shipment_id=? AND p.location_key<>'' ORDER BY s.id
                """,actor.tenantId(),actor.companyId(),shipment);
        int shipmentLines = jdbc.queryForObject("""
                SELECT COUNT(*) FROM shipment_line WHERE tenant_id=? AND company_id=? AND shipment_id=?
                """,Integer.class,actor.tenantId(),actor.companyId(),shipment);
        if (allocations.isEmpty() || shipmentLines!=allocations.size()) throw incomplete("发货单明细缺少实际库位占用");
        var lines = allocations.stream().map(item -> new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,
                item.get("sku_id"),item.get("batch_id"),item.get("location_key"),source.get("target_id"),
                item.get("shipment_line_id"),item.get("qty")}).toList();
        jdbc.batchUpdate("""
                INSERT INTO wms_task_line(id,tenant_id,company_id,task_id,sku_id,batch_id,from_location_id,to_location_id,source_line_id,plan_qty)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,lines);
    }

    @Override
    public void requirePostReady(WarehouseScope actor,long shipment,long warehouse) {
        String lock = TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "" : " FOR SHARE";
        var modes = jdbc.queryForList("""
                SELECT wms_task_mode='required' FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?
                """+lock,Boolean.class,actor.tenantId(),actor.companyId(),warehouse);
        boolean required = !modes.isEmpty() && modes.getFirst();
        var task = work(actor,shipment,lock);
        if (task.isEmpty()) {
            if (required) throw incomplete("仓库要求复核，请重新提交发货单生成拣货任务");
            return;
        }
        var current = task.getFirst();
        if (Boolean.TRUE.equals(current.get("short_pick"))) throw incomplete("拣货存在短拣，请作废发货单后按实际数量重建");
        boolean started = current.get("started_at")!=null;
        if (!required && !started && !Boolean.TRUE.equals(current.get("required_at_submit"))) return;
        if (!"done".equals(current.get("status"))) throw incomplete("请先完成发货拣货");
        var packed = jdbc.queryForList("""
                SELECT id FROM wms_pack WHERE tenant_id=? AND company_id=? AND shipment_id=? AND status='done' LIMIT 1
                """+lock,Long.class,actor.tenantId(),actor.companyId(),shipment);
        if (packed.isEmpty()) throw incomplete("请先完成发货复核装箱");
    }

    @Override
    public void posted(WarehouseScope actor,long shipment) {
        jdbc.update("""
                UPDATE wms_task SET status='cancelled',version=version+1
                WHERE tenant_id=? AND company_id=? AND source_type='shipment' AND source_id=?
                  AND status IN ('created','assigned','in_progress')
                """,actor.tenantId(),actor.companyId(),shipment);
    }

    @Override
    public void cancel(WarehouseScope actor,long shipment,LocalDate day) {
        var active = work(actor,shipment," FOR UPDATE");
        if (active.isEmpty()) return;
        var task = active.getFirst();
        var moved = jdbc.queryForList("""
                SELECT id,sku_id,batch_id,from_location_id,to_location_id,source_line_id,plan_qty,scan_serials
                FROM wms_task_line WHERE tenant_id=? AND company_id=? AND task_id=?
                  AND done_qty=plan_qty AND from_location_id<>to_location_id ORDER BY sku_id,id FOR UPDATE
                """,actor.tenantId(),actor.companyId(),task.get("id"));
        if (!moved.isEmpty()) restore((CompanyAccess)actor,value(task,"id"),value(task,"warehouse_id"),moved,day);
        jdbc.update("""
                UPDATE wms_task SET status='cancelled',version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """,actor.tenantId(),actor.companyId(),task.get("id"));
        jdbc.update("""
                UPDATE wms_pack SET status='cancelled' WHERE tenant_id=? AND company_id=? AND shipment_id=? AND status='done'
                """,actor.tenantId(),actor.companyId(),shipment);
    }

    private void restore(CompanyAccess actor,long task,long warehouse,List<Map<String,Object>> rows,LocalDate day) {
        long period = finance.period(actor,day);
        for (var row : rows) {
            long confirmation = Ids.next();
            var serials = inventory.decodeSelection(row.get("scan_serials"));
            boolean restored = placement.returnToSource(actor,new ShipmentLocationMoveService.Move(warehouse,value(row,"sku_id"),
                    row.get("batch_id")==null ? null : value(row,"batch_id"),value(row,"to_location_id"),value(row,"from_location_id"),
                    (BigDecimal)row.get("plan_qty"),serials==null || serials.serials()==null ? List.of() : serials.serials(),
                    value(row,"source_line_id"),task,confirmation,day,period));
            if (!restored) continue;
            jdbc.update("""
                    INSERT INTO wms_task_confirmation(id,tenant_id,company_id,task_id,task_line_id,to_location_id,qty,operator_id)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,confirmation,actor.tenantId(),actor.companyId(),task,row.get("id"),row.get("from_location_id"),
                    row.get("plan_qty"),actor.userId());
        }
    }

    private List<Map<String,Object>> work(WarehouseScope actor,long shipment,String lock) {
        return jdbc.queryForList("""
                SELECT id,warehouse_id,status,short_pick,required_at_submit,started_at FROM wms_task
                WHERE tenant_id=? AND company_id=? AND source_type='shipment' AND source_id=?
                """+lock,actor.tenantId(),actor.companyId(),shipment);
    }

    private static long value(Map<String,Object> row,String field) {
        return ((Number)row.get(field)).longValue();
    }

    static ApiException incomplete(String message) { return new ApiException(422,"WMS_SHIPMENT_NOT_READY",message); }
}
