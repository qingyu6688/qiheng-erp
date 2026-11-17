package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.ManagedJobs;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.wms.WmsScopes.number;

@Service
class Replenishment {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final int MAX_SERIALS_PER_LINE=500;
    private final JdbcTemplate jdbc;
    private final WmsScopes scopes;
    private final LocationRules rules;
    private final WmsMoves moves;
    private final BusinessCommands commands;

    Replenishment(JdbcTemplate jdbc,WmsScopes scopes,LocationRules rules,WmsMoves moves,BusinessCommands commands) {
        this.jdbc=jdbc;
        this.scopes=scopes;
        this.rules=rules;
        this.moves=moves;
        this.commands=commands;
    }

    record Warehouse(long warehouseId) {}

    private record Destination(long id,BigDecimal room,String existingLot,boolean fixedLot) {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    List<String> generate(CompanyAccess actor,long warehouse,String key) {
        scopes.fullDepartment(actor);
        scopes.warehouse(actor,warehouse,true);
        return List.of(commands.execute(actor,"wms_replenishment.generate",key,new Warehouse(warehouse),200,
                ()->generateWarehouse(actor,warehouse).toArray(String[]::new),String[].class));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void scheduled(long tenant,long company,long warehouse) {
        var worker=new CompanyAccess(tenant,0,company,false,Set.of(warehouse),Set.of());
        generateWarehouse(worker,warehouse);
    }

    private List<String> generateWarehouse(CompanyAccess actor,long warehouse) {
        var result=new ArrayList<String>();
        var enabled=jdbc.queryForList("""
                SELECT wms_task_mode FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?
                  AND status='active' AND location_managed=1 FOR SHARE
                """,String.class,actor.tenantId(),actor.companyId(),warehouse);
        if(enabled.isEmpty() || "off".equals(enabled.getFirst())) return result;
        for(var rule:rules.replenishmentRules(actor,warehouse)) {
            long ruleId=number(rule,"id");
            if (!jdbc.queryForList("""
                    SELECT id FROM wms_move_doc WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND active_rule_id=?
                    """,Long.class,actor.tenantId(),actor.companyId(),warehouse,ruleId).isEmpty()) continue;
            var lines=plan(actor,warehouse,rule);
            if(!lines.isEmpty()) result.add(Long.toString(moves.generate(actor,warehouse,rule,lines)));
        }
        return result;
    }

    private List<WmsMoves.Line> plan(CompanyAccess actor,long warehouse,Map<String,Object> rule) {
        long sku=number(rule,"sku_id"), fixed=number(rule,"fixed_location_id");
        var available=moves.availableAt(actor,warehouse,sku,fixed);
        if(available.compareTo((BigDecimal)rule.get("min_qty"))>=0) return List.of();
        var targets=jdbc.queryForList("""
                SELECT l.capacity_qty,l.mixed_sku_allowed,l.mixed_batch_allowed,s.serial_managed,s.issue_strategy
                FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                  AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active' AND z.type='storage'
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=? AND s.status='active'
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.id=? AND l.status='active'
                """,sku,actor.tenantId(),actor.companyId(),warehouse,fixed);
        if(targets.isEmpty()) return List.of();
        var target=targets.getFirst();
        var demand=((BigDecimal)rule.get("max_qty")).subtract(available);
        var destination=destination(actor,warehouse,sku,fixed,target,demand);
        var serial=WmsMoves.flag(target.get("serial_managed"));
        return allocate(actor,warehouse,sku,destination,serial,"fefo".equals(target.get("issue_strategy")));
    }

    private Destination destination(CompanyAccess actor,long warehouse,long sku,long fixed,Map<String,Object> target,BigDecimal need) {
        var stock=jdbc.queryForList("""
                SELECT sku_id,lot_key,on_hand_qty FROM inventory_balance WHERE tenant_id=? AND company_id=? AND warehouse_id=?
                  AND location_key=? AND on_hand_qty>0 ORDER BY id
                """,actor.tenantId(),actor.companyId(),warehouse,Long.toString(fixed));
        if(!WmsMoves.flag(target.get("mixed_sku_allowed")) && stock.stream().anyMatch(row->number(row,"sku_id")!=sku))
            return new Destination(fixed,BigDecimal.ZERO,null,false);
        var room=need;
        if(target.get("capacity_qty") instanceof BigDecimal capacity) {
            var quantity=stock.stream().map(row->(BigDecimal)row.get("on_hand_qty")).reduce(BigDecimal.ZERO,BigDecimal::add);
            room=room.min(capacity.subtract(quantity));
        }
        var lot=stock.stream().filter(row->number(row,"sku_id")==sku).map(row->row.get("lot_key").toString()).findFirst().orElse(null);
        return new Destination(fixed,room,lot,!WmsMoves.flag(target.get("mixed_batch_allowed")));
    }

    private List<WmsMoves.Line> allocate(CompanyAccess actor,long warehouse,long sku,Destination destination,boolean serial,boolean fefo) {
        var lines=new ArrayList<WmsMoves.Line>();
        var remaining=destination.room();
        if(remaining.signum()<=0) return lines;
        String lot=destination.existingLot();
        for(var stock:candidates(actor,warehouse,sku,destination.id(),fefo)) {
            String stockLot=stock.get("lot_key").toString();
            if(destination.fixedLot() && lot!=null && !lot.equals(stockLot)) continue;
            Long batch=stockLot.isEmpty()?null:Long.parseLong(stockLot);
            long from=Long.parseLong(stock.get("location_key").toString());
            var quantity=remaining.min((BigDecimal)stock.get("available_qty"));
            if(quantity.signum()<=0) continue;
            var serials=serial ? serials(actor,warehouse,sku,batch,from,quantity) : List.<String>of();
            if(serial) quantity=BigDecimal.valueOf(serials.size());
            if(quantity.signum()==0) continue;
            lines.add(new WmsMoves.Line(sku,batch,from,destination.id(),quantity.toPlainString(),serials));
            remaining=remaining.subtract(quantity);
            if(remaining.signum()==0 || lines.size()==200) break;
            if(destination.fixedLot()) lot=stockLot;
        }
        return lines;
    }

    private List<Map<String,Object>> candidates(CompanyAccess actor,long warehouse,long sku,long fixed,boolean fefo) {
        var day=LocalDate.now(BUSINESS_ZONE);
        var ordering=fefo?"b.expiry_date IS NULL,b.expiry_date,":"";
        return jdbc.queryForList("""
                SELECT i.location_key,i.lot_key,i.on_hand_qty-i.blocked_qty-COALESCE((
                    SELECT SUM(p.qty) FROM inv_shipment_pick p WHERE p.tenant_id=i.tenant_id AND p.company_id=i.company_id
                      AND p.warehouse_id=i.warehouse_id AND p.sku_id=i.sku_id AND p.location_key=i.location_key AND p.lot_key=i.lot_key
                ),0) available_qty
                FROM inventory_balance i
                JOIN wms_location l ON l.tenant_id=i.tenant_id AND l.company_id=i.company_id AND l.warehouse_id=i.warehouse_id
                  AND l.id=CAST(i.location_key AS UNSIGNED) AND l.status='active'
                JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id AND z.warehouse_id=l.warehouse_id
                  AND z.id=l.zone_id AND z.status='active' AND z.type='storage'
                LEFT JOIN inv_batch b ON b.tenant_id=i.tenant_id AND b.company_id=i.company_id AND b.sku_id=i.sku_id
                  AND b.id=CAST(i.lot_key AS UNSIGNED)
                WHERE i.tenant_id=? AND i.company_id=? AND i.warehouse_id=? AND i.sku_id=? AND i.location_key<>?
                  AND i.on_hand_qty>i.blocked_qty AND (i.lot_key='' OR (b.status='normal' AND (b.expiry_date IS NULL OR b.expiry_date>=?)))
                HAVING available_qty>0 ORDER BY
                """+ordering+"i.created_at,l.pick_sequence,i.id LIMIT 200",
                actor.tenantId(),actor.companyId(),warehouse,sku,Long.toString(fixed),day);
    }

    private List<String> serials(CompanyAccess actor,long warehouse,long sku,Long batch,long location,BigDecimal quantity) {
        int limit=quantity.setScale(0,RoundingMode.DOWN).min(BigDecimal.valueOf(MAX_SERIALS_PER_LINE)).intValueExact();
        return jdbc.queryForList("""
                SELECT serial_no FROM inv_serial WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                  AND batch_id<=>? AND location_key=? AND status IN ('in_stock','returned') ORDER BY id LIMIT ?
                """,String.class,actor.tenantId(),actor.companyId(),warehouse,sku,batch,Long.toString(location),limit);
    }
}

@Component
class ReplenishmentJob {
    private final JdbcTemplate jdbc;
    private final Replenishment replenishment;

    ReplenishmentJob(JdbcTemplate jdbc,Replenishment replenishment,ManagedJobs jobs) {
        this.jdbc=jdbc;
        this.replenishment=replenishment;
        jobs.register("wms.replenishment",this::run);
    }

    private void run(long tenant) {
        for(var row:jdbc.queryForList("""
                SELECT DISTINCT r.company_id,r.warehouse_id FROM wms_location_rule r
                JOIN warehouse w ON w.tenant_id=r.tenant_id AND w.company_id=r.company_id AND w.id=r.warehouse_id
                JOIN company c ON c.tenant_id=r.tenant_id AND c.id=r.company_id
                WHERE r.tenant_id=? AND r.min_qty IS NOT NULL AND w.status='active' AND w.wms_task_mode<>'off'
                  AND c.status='active' ORDER BY r.company_id,r.warehouse_id
                """,tenant)) {
            replenishment.scheduled(tenant,number(row,"company_id"),number(row,"warehouse_id"));
        }
    }
}
