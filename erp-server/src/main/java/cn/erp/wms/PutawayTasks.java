package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.LocationMoveService;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PutawayTasks {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> STATES = Set.of("created","assigned","in_progress","done","cancelled");
    private static final String HEAD = """
            SELECT t.id,t.no,t.type,t.warehouse_id,t.source_type,t.source_id,t.status,t.assignee_id,t.version
            FROM wms_task t WHERE t.tenant_id=? AND t.company_id=? AND t.type='putaway'
            """;
    private final JdbcTemplate jdbc;
    private final FinanceSupport finance;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final LocationMoveService stock;
    private final LocationRules rules;

    PutawayTasks(JdbcTemplate jdbc,FinanceSupport finance,BusinessCommands commands,AuditService audit,
                 LocationMoveService stock,LocationRules rules) {
        this.jdbc = jdbc;
        this.finance = finance;
        this.commands = commands;
        this.audit = audit;
        this.stock = stock;
        this.rules = rules;
    }

    record Revision(Long version) {}
    record Confirmation(String fromLocationCode,String toLocationCode,String batchNo,String qty,List<String> serialNos,Long version) {}
    record Mode(String taskMode) {}
    private record PostingTime(LocalDate day,long period) {}

    @Transactional(readOnly = true)
    Map<String,Object> page(CompanyAccess actor,Long warehouse,String status,Long cursor,int limit) {
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0 || status != null && !STATES.contains(status))
            throw invalid("作业列表筛选条件无效");
        if (warehouse != null && !actor.allowsWarehouse(warehouse)) throw missing();
        var sql = new StringBuilder(HEAD);
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        scope(actor,sql,args,"t");
        filter(sql,args,"t.warehouse_id",warehouse);
        filter(sql,args,"t.status",status);
        if (cursor != null) {
            sql.append(" AND t.id<?");
            args.add(cursor);
        }
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        args.add(limit + 1);
        var rows = jdbc.queryForList(sql.toString(),args.toArray());
        var result = new LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor",rows.size() > limit ? rows.get(limit - 1).get("id").toString() : null);
        return result;
    }

    @Transactional(readOnly = true)
    Map<String,Object> detail(CompanyAccess actor,long id) {
        var task = head(actor,id,false);
        var result = ApiRows.row(task);
        var rows = jdbc.queryForList("""
                SELECT l.id,l.sku_id,l.batch_id,l.from_location_id,l.plan_qty,l.done_qty,
                       s.code sku_code,s.name sku_name,b.batch_no,p.code from_location_code
                FROM wms_task_line l
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN wms_location p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.id=l.from_location_id
                LEFT JOIN inv_batch b ON b.tenant_id=l.tenant_id AND b.company_id=l.company_id AND b.id=l.batch_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.task_id=? ORDER BY l.id
                """,actor.tenantId(),actor.companyId(),id);
        result.put("lines",rows.stream().map(line -> {
            var view = ApiRows.row(line);
            view.put("recommendedLocationCode",recommend(actor,value(task,"warehouse_id"),line));
            return view;
        }).toList());
        return result;
    }

    @Transactional
    void claim(CompanyAccess actor,long id,String key,Revision revision) {
        run(actor,id,"claim",key,revision,revision.version(),false,(task,time) -> {
            if (!"created".equals(task.get("status"))) throw conflict("任务已领取或结束，请刷新作业列表");
            jdbc.update("""
                    UPDATE wms_task SET assignee_id=?,status='assigned',version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.userId(),actor.tenantId(),actor.companyId(),id);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void confirm(CompanyAccess actor,long id,long lineId,String key,Confirmation input) {
        var quantity = FinanceSupport.quantity(input.qty());
        var serials = input.serialNos() == null ? List.<String>of() : input.serialNos();
        if (serials.size() > 500 || serials.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 100)
                || serials.stream().distinct().count() != serials.size())
            throw invalid("每次最多确认 500 个互不重复的有效序列号");
        String from = locationCode(input.fromLocationCode()), to = locationCode(input.toLocationCode());
        run(actor,id,"confirm",key,List.of(lineId,input),input.version(),true,(task,time) -> {
            requireAssignee(actor,task);
            moveLine(actor,id,lineId,value(task,"warehouse_id"),from,to,input.batchNo(),quantity,serials,time);
            jdbc.update("""
                    UPDATE wms_task SET status='in_progress',started_at=COALESCE(started_at,UTC_TIMESTAMP(3)),version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.tenantId(),actor.companyId(),id);
        });
    }

    @Transactional
    void finish(CompanyAccess actor,long id,String key,Revision revision) {
        run(actor,id,"finish",key,revision,revision.version(),false,(task,time) -> {
            requireAssignee(actor,task);
            var remaining = jdbc.queryForList("""
                    SELECT id FROM wms_task_line WHERE tenant_id=? AND company_id=? AND task_id=?
                    AND done_qty<plan_qty LIMIT 1 FOR SHARE
                    """,Long.class,actor.tenantId(),actor.companyId(),id);
            if (!remaining.isEmpty()) throw conflict("仍有剩余数量未上架");
            jdbc.update("""
                    UPDATE wms_task SET status='done',finished_at=UTC_TIMESTAMP(3),version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.tenantId(),actor.companyId(),id);
        });
    }

    private void run(CompanyAccess actor,long id,String action,String key,Object input,Long version,boolean movesStock,
                     BiConsumer<Map<String,Object>,PostingTime> operation) {
        var snapshot = head(actor,id,false);
        commands.execute(actor,"wms_task."+action,key,List.of(id,input),200,() -> {
            // 期间先于来源单据加锁，保持与原入库冲销一致。
            var day = LocalDate.now(BUSINESS_ZONE);
            var time = movesStock ? new PostingTime(day,finance.period(actor,day)) : null;
            requireOpenSource(actor,snapshot);
            if (movesStock) requireNoActiveReversal(actor,snapshot);
            var task = head(actor,id,true);
            FinanceSupport.version(task,version);
            operation.accept(task,time);
            audit.record(actor,"wms_task."+action,"wms_task",id,ApiRows.row(task),ApiRows.row(head(actor,id,true)));
            return Long.toString(id);
        },String.class);
    }

    private void requireOpenSource(CompanyAccess actor,Map<String,Object> task) {
        var source = PutawayTaskWriter.Source.of(task.get("source_type").toString());
        var available = jdbc.queryForList("""
                SELECT id FROM %s
                WHERE tenant_id=? AND company_id=? AND id=? AND warehouse_id=? AND status='posted' FOR UPDATE
                """.formatted(source.table()),
                Long.class,actor.tenantId(),actor.companyId(),task.get("source_id"),task.get("warehouse_id"));
        if (available.isEmpty()) throw conflict("来源单据已冲销或不可作业");
    }

    private void requireNoActiveReversal(CompanyAccess actor,Map<String,Object> task) {
        // 上架与冲销均已锁定来源单据。使用当前读检查由先取得来源锁的事务提交的冲销。
        var reversals = jdbc.queryForList("""
                SELECT id FROM document_reversal WHERE tenant_id=? AND company_id=? AND source_type=?
                AND active_source_key=? AND status IN ('pending','approved') FOR SHARE
                """,Long.class,actor.tenantId(),actor.companyId(),task.get("source_type"),task.get("source_id"));
        if (!reversals.isEmpty())
            throw new ApiException(409,"WMS_PUTAWAY_REVERSAL_PENDING","原单有待审核或待过账的冲销，请先处理冲销单");
    }

    private void moveLine(CompanyAccess actor,long task,long lineId,long warehouse,String from,String to,String batch,
                          BigDecimal quantity,List<String> serials,PostingTime time) {
        var activeWarehouse = jdbc.queryForList("""
                SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'
                  AND location_managed=1 FOR SHARE
                """,Long.class,actor.tenantId(),actor.companyId(),warehouse);
        if (activeWarehouse.isEmpty()) throw conflict("仓库未启用库位管理");
        var rows = jdbc.queryForList("""
                SELECT l.*,s.code sku_code,b.batch_no,p.code from_location_code
                FROM wms_task_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN wms_location p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.id=l.from_location_id
                LEFT JOIN inv_batch b ON b.tenant_id=l.tenant_id AND b.company_id=l.company_id AND b.id=l.batch_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.task_id=? AND l.id=? FOR UPDATE OF l
                """,actor.tenantId(),actor.companyId(),task,lineId);
        if (rows.isEmpty()) throw missing();
        var line = rows.getFirst();
        if (!from.equalsIgnoreCase(line.get("from_location_code").toString())) throw invalid("扫描的源库位与作业行不一致");
        validateBatch(batch,line);
        if (remaining(line).compareTo(quantity) < 0) throw conflict("上架数量超过当前剩余数量");
        verifySerials(actor,line,serials);
        long destination = storageLocation(actor,warehouse,to);
        long confirmation = Ids.next();
        Long batchId = line.get("batch_id")==null ? null : value(line,"batch_id");
        stock.move(actor,warehouse,value(line,"sku_id"),batchId,value(line,"from_location_id"),destination,
                quantity,serials,task,confirmation,time.day(),time.period());
        jdbc.update("""
                INSERT INTO wms_task_confirmation(id,tenant_id,company_id,task_id,task_line_id,to_location_id,qty,operator_id)
                VALUES(?,?,?,?,?,?,?,?)
                """,confirmation,actor.tenantId(),actor.companyId(),task,lineId,destination,quantity,actor.userId());
        jdbc.update("""
                UPDATE wms_task_line SET done_qty=done_qty+? WHERE tenant_id=? AND company_id=? AND task_id=? AND id=?
                """,quantity,actor.tenantId(),actor.companyId(),task,lineId);
    }

    private void verifySerials(CompanyAccess actor,Map<String,Object> line,List<String> serials) {
        if (serials.isEmpty()) return;
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),line.get("receipt_movement_id")));
        args.addAll(serials);
        var included = jdbc.queryForList("""
                SELECT s.serial_no FROM inv_movement_serial m JOIN inv_serial s
                    ON s.tenant_id=m.tenant_id AND s.company_id=m.company_id AND s.id=m.serial_id
                WHERE m.tenant_id=? AND m.company_id=? AND m.movement_id=? AND s.serial_no IN (%s)
                """.formatted(placeholders(serials.size())),String.class,args.toArray());
        if (included.size() != serials.size()) throw invalid("序列号不属于该入库明细");
    }

    private String recommend(CompanyAccess actor,long warehouse,Map<String,Object> line) {
        var remaining = remaining(line);
        if (remaining.signum() <= 0) return null;
        long sku = value(line,"sku_id");
        String lot = Objects.toString(line.get("batch_id"),"");
        var target = rules.target(actor,warehouse,sku);
        return jdbc.queryForList("""
                SELECT l.code FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                    AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active' AND z.type='storage'
                LEFT JOIN inventory_balance b ON b.tenant_id=l.tenant_id AND b.company_id=l.company_id
                    AND b.warehouse_id=l.warehouse_id AND b.location_key=CAST(l.id AS CHAR) AND b.on_hand_qty>0
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.status='active' AND l.id<>?
                GROUP BY l.id,l.code,l.zone_id,l.capacity_qty,l.mixed_sku_allowed,l.mixed_batch_allowed,l.pick_sequence
                HAVING (l.capacity_qty IS NULL OR COALESCE(SUM(b.on_hand_qty),0)+?<=l.capacity_qty)
                  AND (l.mixed_sku_allowed OR COALESCE(SUM(b.sku_id<>?),0)=0)
                  AND (l.mixed_batch_allowed OR COALESCE(SUM(b.sku_id=? AND b.lot_key<>?),0)=0)
                  AND (l.id<=>? OR COALESCE(SUM(b.sku_id=? AND (b.lot_key=? OR l.mixed_batch_allowed)),0)>0
                       OR (COUNT(b.id)=0 AND (? IS NULL OR l.zone_id=?)))
                ORDER BY (l.id<=>?) DESC,(COALESCE(SUM(b.sku_id=? AND b.lot_key=?),0)>0) DESC,
                         COUNT(b.id)=0,l.pick_sequence,l.id LIMIT 1
                """,String.class,actor.tenantId(),actor.companyId(),warehouse,line.get("from_location_id"),
                remaining,sku,sku,lot,target.fixedLocationId(),sku,lot,target.zoneId(),target.zoneId(),
                target.fixedLocationId(),sku,lot).stream().findFirst().orElse(null);
    }

    private long storageLocation(CompanyAccess actor,long warehouse,String code) {
        return jdbc.queryForList("""
                SELECT l.id FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                    AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active' AND z.type='storage'
                JOIN warehouse w ON w.tenant_id=l.tenant_id AND w.company_id=l.company_id AND w.id=l.warehouse_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.code=? AND l.status='active'
                  AND w.status='active' AND w.location_managed=1 FOR SHARE OF w
                """,Long.class,actor.tenantId(),actor.companyId(),warehouse,code).stream().findFirst()
                .orElseThrow(() -> invalid("请选择当前仓库已启用的存储库位"));
    }

    private void validateBatch(String scanned,Map<String,Object> line) {
        String batch = Objects.toString(scanned,"").strip();
        if (batch.startsWith("B:")) {
            String prefix = "B:"+line.get("sku_code")+"|";
            if (!batch.regionMatches(true,0,prefix,0,prefix.length())) throw invalid("扫描批次的商品编码与作业行不一致");
            batch = batch.substring(prefix.length());
        }
        if (!batch.equals(Objects.toString(line.get("batch_no"),""))) throw invalid("扫描的批次号与作业行不一致");
    }

    @Transactional(readOnly = true)
    List<Map<String,Object>> warehouses(CompanyAccess actor) {
        var sql = new StringBuilder("SELECT w.id,w.code,w.name,w.wms_task_mode task_mode,w.location_managed FROM warehouse w"
                +" WHERE w.tenant_id=? AND w.company_id=? AND w.status='active'");
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        warehouseScope(actor,sql,args,"w.id");
        return jdbc.queryForList(sql.append(" ORDER BY w.code,w.id").toString(),args.toArray()).stream().map(ApiRows::row).toList();
    }

    @Transactional
    void mode(CompanyAccess actor,long warehouse,Mode mode) {
        if (!actor.allowsWarehouse(warehouse)) throw missing();
        if (!actor.orgScope().allDepartments()) throw new ApiException(403,"FORBIDDEN","设置作业模式需要当前公司全部部门范围");
        String requested = mode.taskMode();
        if (requested==null || !Set.of("off","optional","required").contains(requested))
            throw invalid("当前可选择关闭、可选作业或强制复核");
        var rows = jdbc.queryForList("""
                SELECT wms_task_mode,location_managed,default_receiving_location_id,default_shipping_location_id FROM warehouse
                WHERE tenant_id=? AND company_id=? AND id=? AND status='active' FOR UPDATE
                """,actor.tenantId(),actor.companyId(),warehouse);
        if (rows.isEmpty()) throw missing();
        var current = rows.getFirst();
        if (!"off".equals(requested) && !canEnable(actor,warehouse,current))
            throw invalid("请先启用库位管理，并设置启用的收货区默认库位");
        if ("required".equals(requested) && !hasDefaultLocation(actor,warehouse,current.get("default_shipping_location_id"),"shipping"))
            throw invalid("强制复核需配置启用的发货区默认库位");
        if (requested.equals(current.get("wms_task_mode"))) return;
        jdbc.update("""
                UPDATE warehouse SET wms_task_mode=?,updated_by=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,requested,actor.userId(),actor.tenantId(),actor.companyId(),warehouse);
        audit.record(actor,"wms_task.mode","warehouse",warehouse,Map.of("taskMode",current.get("wms_task_mode")),Map.of("taskMode",requested));
    }

    private boolean canEnable(CompanyAccess actor,long warehouse,Map<String,Object> current) {
        Object managed = current.get("location_managed");
        if (!Boolean.TRUE.equals(managed) && !"1".equals(String.valueOf(managed))) return false;
        return hasDefaultLocation(actor,warehouse,current.get("default_receiving_location_id"),"receiving");
    }

    private boolean hasDefaultLocation(CompanyAccess actor,long warehouse,Object location,String type) {
        if (location==null) return false;
        return !jdbc.queryForList("""
                SELECT l.id FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                    AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id AND z.status='active' AND z.type=?
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.id=? AND l.status='active' FOR SHARE
                """,Long.class,type,actor.tenantId(),actor.companyId(),warehouse,location).isEmpty();
    }

    private Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var sql = new StringBuilder(HEAD).append(" AND t.id=?");
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),id));
        scope(actor,sql,args,"t");
        if (lock) sql.append(" FOR UPDATE");
        return jdbc.queryForList(sql.toString(),args.toArray()).stream().findFirst().orElseThrow(PutawayTasks::missing);
    }

    private void scope(CompanyAccess actor,StringBuilder sql,List<Object> args,String alias) {
        actor.orgScope().appendOwnerFilter(sql,args,alias);
        warehouseScope(actor,sql,args,alias+".warehouse_id");
    }

    private void warehouseScope(CompanyAccess actor,StringBuilder sql,List<Object> args,String field) {
        if (actor.allWarehouses()) return;
        if (actor.warehouseIds().isEmpty()) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ").append(field).append(" IN (").append(placeholders(actor.warehouseIds().size())).append(')');
        args.addAll(actor.warehouseIds());
    }

    private static void filter(StringBuilder sql,List<Object> args,String field,Object value) {
        if (value != null) {
            sql.append(" AND ").append(field).append("=?");
            args.add(value);
        }
    }

    private static void requireAssignee(CompanyAccess actor,Map<String,Object> task) {
        if (!Set.of("assigned","in_progress").contains(task.get("status"))) throw conflict("请领取未完成的上架任务");
        if (task.get("assignee_id") == null || value(task,"assignee_id") != actor.userId())
            throw new ApiException(403,"FORBIDDEN","只有当前领取人可以执行上架任务");
    }

    private static BigDecimal remaining(Map<String,Object> line) {
        return ((BigDecimal)line.get("plan_qty")).subtract((BigDecimal)line.get("done_qty"));
    }

    private static String locationCode(String input) {
        String code = Objects.toString(input,"").strip();
        if (code.startsWith("L:")) code = code.substring(2);
        if (code.isEmpty() || code.length() > 48 || code.codePoints().anyMatch(Character::isISOControl))
            throw invalid("请输入有效的库位编码");
        return code;
    }

    private static long value(Map<String,Object> row,String field) {
        return ((Number)row.get(field)).longValue();
    }

    private static String placeholders(int size) {
        return String.join(",",Collections.nCopies(size,"?"));
    }

    private static ApiException missing() { return new ApiException(404,"NOT_FOUND","作业或仓库不存在或无权查看"); }
    private static ApiException conflict(String message) { return new ApiException(409,"STATE_CONFLICT",message); }
    private static ApiException invalid(String message) { return new ApiException(422,"VALIDATION_FAILED",message); }
}
