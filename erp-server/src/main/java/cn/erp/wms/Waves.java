package cn.erp.wms;

import static cn.erp.wms.PickTasks.*;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Waves {
    private static final Set<String> RULES = Set.of("manual","carrier","cutoff","customer");
    private static final String HEAD = """
            SELECT w.id,w.no,w.warehouse_id,w.rule,w.cutoff_at,w.assignee_id,w.version,w.created_at,
              CASE
                WHEN NOT EXISTS (SELECT 1 FROM wms_task t WHERE t.tenant_id=w.tenant_id AND t.company_id=w.company_id AND t.wave_id=w.id AND t.status<>'cancelled') THEN 'cancelled'
                WHEN NOT EXISTS (SELECT 1 FROM wms_task t WHERE t.tenant_id=w.tenant_id AND t.company_id=w.company_id AND t.wave_id=w.id AND t.status NOT IN ('done','cancelled')) THEN 'done'
                WHEN EXISTS (SELECT 1 FROM wms_task t WHERE t.tenant_id=w.tenant_id AND t.company_id=w.company_id AND t.wave_id=w.id AND t.status IN ('in_progress','done')) THEN 'in_progress'
                WHEN w.assignee_id IS NULL THEN 'created' ELSE 'assigned'
              END status
            FROM wms_wave w WHERE w.tenant_id=? AND w.company_id=?
            """;
    private final JdbcTemplate jdbc;
    private final PickTasks picks;
    private final BusinessCommands commands;
    private final AuditService audit;

    Waves(JdbcTemplate jdbc,PickTasks picks,BusinessCommands commands,AuditService audit) {
        this.jdbc = jdbc;
        this.picks = picks;
        this.commands = commands;
        this.audit = audit;
    }

    record Create(List<Long> shipmentIds,String rule,Instant cutoffAt) {}
    record Revision(Long version) {}

    @Transactional(readOnly = true)
    Map<String,Object> page(CompanyAccess actor,Long warehouse,Long cursor,int limit) {
        checkPage(cursor,limit);
        if (warehouse!=null && !actor.allowsWarehouse(warehouse)) throw missing();
        var sql = new StringBuilder(HEAD);
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        scopeWave(actor,sql,args);
        filter(sql,args,"w.warehouse_id",warehouse);
        if (cursor!=null) {
            sql.append(" AND w.id<?");
            args.add(cursor);
        }
        sql.append(" ORDER BY w.id DESC LIMIT ?");
        args.add(limit+1);
        return pageRows(jdbc.queryForList(sql.toString(),args.toArray()).stream().map(ApiRows::row).toList(),limit);
    }

    @Transactional(readOnly = true)
    Map<String,Object> detail(CompanyAccess actor,long id) {
        var detail = ApiRows.row(head(actor,id,false));
        var members = members(actor,id);
        detail.put("shipments",members.stream().map(picks::taskView).toList());
        var lines = members.stream().flatMap(m -> picks.lines(actor,value(m,"id"),false).stream())
                .sorted(Comparator.<Map<String,Object>>comparingLong(m -> value(m,"pick_sequence"))
                        .thenComparing(m -> m.get("from_location_code").toString())
                        .thenComparing(m -> m.get("shipment_no").toString()).thenComparingLong(m -> value(m,"id")))
                .map(picks::lineView).toList();
        detail.put("lines",lines);
        return detail;
    }

    @Transactional
    Map<String,Object> create(CompanyAccess actor,String key,Create input) {
        if (input.shipmentIds()==null || input.shipmentIds().size()<2 || input.shipmentIds().size()>50
                || input.shipmentIds().stream().anyMatch(id -> id==null || id<=0))
            throw invalid("请选择 2 到 50 张同仓待拣发货单");
        var shipments = input.shipmentIds().stream().distinct().sorted().toList();
        if (shipments.size()!=input.shipmentIds().size()) throw invalid("同一张发货单不能重复加入波次");
        String rule = input.rule()==null || input.rule().isBlank() ? "manual" : input.rule();
        if (!RULES.contains(rule) || "cutoff".equals(rule) != (input.cutoffAt()!=null)) throw invalid("请选择有效的波次规则及截单时间");
        shipments.forEach(id -> picks.head(actor,"t.source_id",id,false));
        String id = commands.execute(actor,"wms_wave.create",key,input,200,() ->
                Long.toString(start(actor,shipments,rule,input.cutoffAt())),String.class);
        return ApiRows.row(head(actor,Long.parseLong(id),false));
    }

    private long start(CompanyAccess actor,List<Long> shipments,String rule,Instant cutoff) {
        shipments.forEach(id -> picks.lockShipment(actor,id));
        var tasks = shipments.stream().map(id -> picks.head(actor,"t.source_id",id,true)).toList();
        if (tasks.stream().anyMatch(t -> t.get("wave_id")!=null || !"created".equals(t.get("status"))))
            throw conflict("波次只接受尚未领取且未加入其他波次的发货单");
        long warehouse = value(tasks.getFirst(),"warehouse_id");
        if (tasks.stream().anyMatch(t -> value(t,"warehouse_id")!=warehouse)) throw invalid("波次不能包含不同仓库的发货单");
        checkRule(tasks,rule,cutoff);
        long wave = Ids.next();
        jdbc.update("""
                INSERT INTO wms_wave(id,tenant_id,company_id,warehouse_id,no,rule,cutoff_at,created_by)
                VALUES(?,?,?,?,?,?,?,?)
                """,wave,actor.tenantId(),actor.companyId(),warehouse,"WV-"+wave,rule,
                cutoff==null ? null : java.sql.Timestamp.from(cutoff),actor.userId());
        var args = tasks.stream().map(t -> new Object[]{wave,actor.tenantId(),actor.companyId(),t.get("id")}).toList();
        jdbc.batchUpdate("UPDATE wms_task SET wave_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",args);
        audit.record(actor,"wms_wave.create","wms_wave",wave,null,Map.of("shipmentIds",shipments,"rule",rule));
        return wave;
    }

    private void checkRule(List<Map<String,Object>> tasks,String rule,Instant cutoff) {
        if ("carrier".equals(rule)) {
            Object carrier = tasks.getFirst().get("carrier_code");
            if (carrier==null || tasks.stream().anyMatch(t ->
                    !"delivery".equals(t.get("delivery_method")) || !Objects.equals(carrier,t.get("carrier_code"))))
                throw invalid("承运商波次只包含登记同一承运商的发货单");
        }
        if ("customer".equals(rule)) {
            long customer = value(tasks.getFirst(),"customer_id");
            if (tasks.stream().anyMatch(t -> value(t,"customer_id")!=customer)) throw invalid("客户波次只包含同一客户的发货单");
        }
        if ("cutoff".equals(rule) && tasks.stream().anyMatch(t -> submitted(t.get("submitted_at")).isAfter(cutoff)))
            throw invalid("波次存在晚于截单时间提交的发货单");
    }

    private Instant submitted(Object value) {
        if (value instanceof LocalDateTime date) return date.toInstant(ZoneOffset.UTC);
        return ((java.sql.Timestamp)value).toInstant();
    }

    @Transactional
    void claim(CompanyAccess actor,long id,String key,Revision input) {
        head(actor,id,false);
        commands.execute(actor,"wms_wave.claim",key,List.of(id,input),200,() -> {
            var active = members(actor,id).stream().filter(t -> !"cancelled".equals(t.get("status"))).toList();
            active.stream().map(t -> value(t,"shipment_id")).sorted().forEach(shipment -> picks.lockShipment(actor,shipment));
            var wave = head(actor,id,true);
            FinanceSupport.version(wave,input.version());
            if (active.isEmpty() || wave.get("assignee_id")!=null
                    || active.stream().anyMatch(t -> !"created".equals(picks.head(actor,"t.id",value(t,"id"),true).get("status"))))
                throw conflict("波次已领取或发货单状态已经变化");
            jdbc.update("""
                    UPDATE wms_wave SET assignee_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.userId(),actor.tenantId(),actor.companyId(),id);
            jdbc.update("""
                    UPDATE wms_task SET assignee_id=?,status='assigned',version=version+1
                    WHERE tenant_id=? AND company_id=? AND wave_id=? AND status='created'
                    """,actor.userId(),actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"wms_wave.claim","wms_wave",id,null,Map.of("assigneeId",Long.toString(actor.userId())));
            return Long.toString(id);
        },String.class);
    }

    private List<Map<String,Object>> members(CompanyAccess actor,long wave) {
        return jdbc.queryForList(PickTasks.HEAD+" AND t.wave_id=? ORDER BY t.id",actor.tenantId(),actor.companyId(),wave);
    }

    private Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var sql = new StringBuilder(HEAD).append(" AND w.id=?");
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),id));
        scopeWave(actor,sql,args);
        if (lock) sql.append(" FOR UPDATE OF w");
        return jdbc.queryForList(sql.toString(),args.toArray()).stream().findFirst().orElseThrow(PickTasks::missing);
    }

    private void scopeWave(CompanyAccess actor,StringBuilder sql,List<Object> args) {
        warehouseScope(actor,sql,args,"w");
        // 任一成员超出当前部门范围时，不可读取或领取整个波次。
        sql.append("""
                 AND NOT EXISTS (SELECT 1 FROM wms_task t WHERE t.tenant_id=w.tenant_id AND t.company_id=w.company_id
                    AND t.wave_id=w.id AND NOT (
                """).append(actor.orgScope().ownerFilter("t")).append("))");
    }
}
