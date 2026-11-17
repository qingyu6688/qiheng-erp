package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.ShipmentLocationMoveService;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class PickTasks {
    private static final Set<String> STATES = Set.of("created","assigned","in_progress","done","cancelled");
    static final String HEAD = """
            SELECT t.id,t.no,t.warehouse_id,t.source_id shipment_id,s.shipment_no,t.status,t.assignee_id,t.version,
                   t.wave_id,t.short_pick,t.owner_department_id,t.owner_user_id,s.carrier_code,s.submitted_at,s.customer_id,
                   s.business_date,s.delivery_method
            FROM wms_task t JOIN shipment s ON s.tenant_id=t.tenant_id AND s.company_id=t.company_id AND s.id=t.source_id
            WHERE t.tenant_id=? AND t.company_id=? AND t.type='pick' AND t.source_type='shipment'
            """;
    private static final String LINES = """
            SELECT l.id,l.task_id,l.sku_id,t.source_id shipment_id,h.shipment_no,l.source_line_id,l.batch_id,
                   l.from_location_id,l.to_location_id,l.plan_qty,l.done_qty,l.scan_serials,s.code sku_code,s.name sku_name,
                   f.code from_location_code,d.code to_location_code,f.pick_sequence,b.batch_no,
                   p.serial_ids,p.shipment_line_id allocation_id
            FROM wms_task_line l JOIN wms_task t ON t.tenant_id=l.tenant_id AND t.company_id=l.company_id AND t.id=l.task_id
            JOIN shipment h ON h.tenant_id=t.tenant_id AND h.company_id=t.company_id AND h.id=t.source_id
            JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
            JOIN wms_location f ON f.tenant_id=t.tenant_id AND f.company_id=t.company_id AND f.warehouse_id=t.warehouse_id AND f.id=l.from_location_id
            JOIN wms_location d ON d.tenant_id=t.tenant_id AND d.company_id=t.company_id AND d.warehouse_id=t.warehouse_id AND d.id=l.to_location_id
            LEFT JOIN inv_batch b ON b.tenant_id=l.tenant_id AND b.company_id=l.company_id AND b.id=l.batch_id
            LEFT JOIN inv_shipment_pick p ON p.tenant_id=l.tenant_id AND p.company_id=l.company_id AND p.shipment_line_id=l.source_line_id
            WHERE l.tenant_id=? AND l.company_id=? AND t.type='pick'
            """;
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final ShipmentLocationMoveService stock;
    private final FinanceSupport finance;
    private final BusinessCommands commands;
    private final AuditService audit;

    PickTasks(JdbcTemplate jdbc,InventoryService inventory,ShipmentLocationMoveService stock,FinanceSupport finance,
              BusinessCommands commands,AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.stock = stock;
        this.finance = finance;
        this.commands = commands;
        this.audit = audit;
    }

    record Revision(Long version,Boolean shortPick) {}
    record Scan(Long version,String fromLocationCode,String toLocationCode,String batchNo,String qty,List<String> serialNos) {}

    @Transactional(readOnly = true)
    Map<String,Object> page(CompanyAccess actor,Long warehouse,String status,Long cursor,int limit) {
        checkPage(cursor,limit);
        if (status!=null && !STATES.contains(status)) throw invalid("拣货状态筛选无效");
        if (warehouse!=null && !actor.allowsWarehouse(warehouse)) throw missing();
        var sql = new StringBuilder(HEAD);
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        scope(actor,sql,args,"t");
        filter(sql,args,"t.warehouse_id",warehouse);
        filter(sql,args,"t.status",status);
        if (cursor!=null) {
            sql.append(" AND t.id<?");
            args.add(cursor);
        }
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        args.add(limit+1);
        return pageRows(jdbc.queryForList(sql.toString(),args.toArray()).stream().map(this::taskView).toList(),limit);
    }

    @Transactional(readOnly = true)
    Map<String,Object> detail(CompanyAccess actor,long task) {
        var view = taskView(head(actor,"t.id",task,false));
        view.put("lines",lines(actor,task,false).stream().map(this::lineView).toList());
        return view;
    }

    @Transactional
    void claim(CompanyAccess actor,long task,String key,Revision input) {
        command(actor,task,"claim",key,input,input.version(),current -> {
            if (!"created".equals(current.get("status"))) throw conflict("拣货任务已领取或结束");
            if (current.get("wave_id")!=null) throw conflict("该任务归属波次，请领取整个波次");
            jdbc.update("""
                    UPDATE wms_task SET status='assigned',assignee_id=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.userId(),actor.tenantId(),actor.companyId(),task);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void confirm(CompanyAccess actor,long task,long line,String key,Scan input) {
        var snapshot = head(actor,"t.id",task,false);
        var quantity = FinanceSupport.quantity(input.qty());
        var scanned = serials(input.serialNos());
        if (input.fromLocationCode()==null || input.toLocationCode()==null) throw invalid("请扫描源库位和发货库位");
        commands.execute(actor,"wms_pick.confirm",key,List.of(task,line,input),200,() -> {
            var rawDay = snapshot.get("business_date");
            var day = rawDay instanceof LocalDate date ? date : ((java.sql.Date)rawDay).toLocalDate();
            long period = finance.period(actor,day);
            lockShipment(actor,value(snapshot,"shipment_id"));
            var current = head(actor,"t.id",task,true);
            FinanceSupport.version(current,input.version());
            checkOwner(actor,current);
            var rows = jdbc.queryForList(LINES+" AND l.task_id=? AND l.id=? FOR UPDATE OF l",
                    actor.tenantId(),actor.companyId(),task,line);
            if (rows.isEmpty()) throw missing();
            registerScan(actor,current,rows.getFirst(),input,quantity,scanned,day,period);
            jdbc.update("""
                    UPDATE wms_task SET status='in_progress',started_at=COALESCE(started_at,UTC_TIMESTAMP(3)),version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.tenantId(),actor.companyId(),task);
            audit.record(actor,"wms_pick.confirm","wms_task",task,null,Map.of("lineId",Long.toString(line),"qty",quantity.toPlainString()));
            return Long.toString(task);
        },String.class);
    }

    private void registerScan(CompanyAccess actor,Map<String,Object> task,Map<String,Object> line,Scan input,BigDecimal quantity,
                              List<String> newSerials,LocalDate day,long period) {
        if (line.get("allocation_id")==null) throw conflict("发货占用已失效，请处理发货单");
        if (!locationMatches(input.fromLocationCode(),line.get("from_location_code"))
                || !locationMatches(input.toLocationCode(),line.get("to_location_code")))
            throw invalid("扫描的源库位或发货库位与任务不一致");
        checkBatch(input.batchNo(),line);
        BigDecimal plan = decimal(line,"plan_qty");
        BigDecimal total = decimal(line,"done_qty").add(quantity);
        if (total.compareTo(plan)>0) throw conflict("扫描数量超过剩余拣货数量");
        var scanned = mergeSerials(line,quantity,newSerials);
        long id = value(task,"id");
        if (total.compareTo(plan)==0) move(actor,task,line,scanned,day,period);
        jdbc.update("""
                UPDATE wms_task_line SET done_qty=?,scan_serials=?
                WHERE tenant_id=? AND company_id=? AND task_id=? AND id=?
                """,total,inventory.encodeSelection(new StockSelection(null,null,null,null,null,scanned)),
                actor.tenantId(),actor.companyId(),id,line.get("id"));
    }

    private List<String> mergeSerials(Map<String,Object> line,BigDecimal quantity,List<String> scanned) {
        var allocation = selectionSerials(line.get("serial_ids"));
        if (allocation.isEmpty()) {
            if (!scanned.isEmpty()) throw invalid("该明细无需扫描序列号");
            return List.of();
        }
        if (quantity.compareTo(BigDecimal.valueOf(scanned.size()))!=0)
            throw invalid("序列号扫描数量与本次数量不一致");
        var collected = new HashSet<>(selectionSerials(line.get("scan_serials")));
        var expected = Set.copyOf(allocation);
        for (String serial : scanned) {
            if (!expected.contains(serial) || !collected.add(serial)) throw invalid("序列号不属于该明细或已扫描");
        }
        return collected.stream().sorted().toList();
    }

    private void move(CompanyAccess actor,Map<String,Object> task,Map<String,Object> line,List<String> scanned,LocalDate day,long period) {
        long confirmation = Ids.next(), id = value(task,"id");
        stock.stage(actor,new ShipmentLocationMoveService.Move(value(task,"warehouse_id"),value(line,"sku_id"),
                nullable(line,"batch_id"),value(line,"from_location_id"),value(line,"to_location_id"),decimal(line,"plan_qty"),
                scanned,value(line,"source_line_id"),id,confirmation,day,period));
        jdbc.update("""
                INSERT INTO wms_task_confirmation(id,tenant_id,company_id,task_id,task_line_id,to_location_id,qty,operator_id)
                VALUES(?,?,?,?,?,?,?,?)
                """,confirmation,actor.tenantId(),actor.companyId(),id,line.get("id"),line.get("to_location_id"),line.get("plan_qty"),actor.userId());
    }

    @Transactional
    void finish(CompanyAccess actor,long task,String key,Revision input) {
        command(actor,task,"finish",key,input,input.version(),current -> {
            checkOwner(actor,current);
            boolean remaining = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM wms_task_line WHERE tenant_id=? AND company_id=? AND task_id=? AND done_qty<plan_qty)
                    """,Boolean.class,actor.tenantId(),actor.companyId(),task));
            if (remaining && !Boolean.TRUE.equals(input.shortPick())) throw conflict("仍有未扫描数量，请继续确认或登记短拣");
            jdbc.update("""
                    UPDATE wms_task SET status='done',short_pick=?,finished_at=UTC_TIMESTAMP(3),version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,remaining,actor.tenantId(),actor.companyId(),task);
        });
    }

    private void command(CompanyAccess actor,long task,String action,String key,Object payload,Long version,Consumer<Map<String,Object>> work) {
        var snapshot = head(actor,"t.id",task,false);
        commands.execute(actor,"wms_pick."+action,key,List.of(task,payload),200,() -> {
            lockShipment(actor,value(snapshot,"shipment_id"));
            var current = head(actor,"t.id",task,true);
            FinanceSupport.version(current,version);
            work.accept(current);
            audit.record(actor,"wms_pick."+action,"wms_task",task,taskView(current),taskView(head(actor,"t.id",task,true)));
            return Long.toString(task);
        },String.class);
    }

    Map<String,Object> head(CompanyAccess actor,String field,long id,boolean locked) {
        var sql = new StringBuilder(HEAD).append(" AND ").append(field).append("=?");
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),id));
        scope(actor,sql,args,"t");
        if (locked) sql.append(" FOR UPDATE OF t");
        return jdbc.queryForList(sql.toString(),args.toArray()).stream().findFirst().orElseThrow(PickTasks::missing);
    }

    void lockShipment(CompanyAccess actor,long shipment) {
        var sql = new StringBuilder("""
                SELECT s.id,s.warehouse_id,s.status FROM shipment s WHERE s.tenant_id=? AND s.company_id=? AND s.id=?
                """);
        var args = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),shipment));
        scope(actor,sql,args,"s");
        var found = jdbc.queryForList(sql+" FOR UPDATE",args.toArray());
        if (found.isEmpty()) throw missing();
        if (!"submitted".equals(found.getFirst().get("status"))) throw conflict("仅待出库发货单可执行仓储作业");
    }

    List<Map<String,Object>> lines(CompanyAccess actor,long task,boolean locked) {
        return jdbc.queryForList(LINES+" AND l.task_id=? ORDER BY f.pick_sequence,f.code,l.id"+(locked?" FOR UPDATE OF l":""),
                actor.tenantId(),actor.companyId(),task);
    }

    Map<String,Object> taskView(Map<String,Object> row) {
        var result = ApiRows.row(row);
        result.keySet().removeAll(Set.of("ownerDepartmentId","ownerUserId"));
        return result;
    }

    Map<String,Object> lineView(Map<String,Object> row) {
        var result = ApiRows.row(row);
        var scanned = selectionSerials(row.get("scan_serials"));
        result.put("serialNos",row.get("allocation_id")==null ? scanned : selectionSerials(row.get("serial_ids")));
        result.put("scannedSerialNos",scanned);
        result.keySet().removeAll(Set.of("scanSerials","serialIds","allocationId"));
        return result;
    }

    List<String> selectionSerials(Object raw) {
        var selection = inventory.decodeSelection(raw);
        return selection==null || selection.serials()==null ? List.of() : selection.serials();
    }

    static void checkBatch(String input,Map<String,Object> line) {
        String code = Objects.toString(input,"").strip();
        String batch = Objects.toString(line.get("batch_no"),"");
        if (batch.isEmpty() && code.isEmpty()) return;
        String prefix = "B:"+line.get("sku_code")+"|";
        if (code.regionMatches(true,0,prefix,0,prefix.length())) code = code.substring(prefix.length());
        if (!code.equals(batch)) throw invalid("扫描的批次与任务明细不一致");
    }

    private boolean locationMatches(String input,Object code) {
        String scanned = input.strip();
        if (scanned.regionMatches(true,0,"L:",0,2)) scanned = scanned.substring(2);
        return scanned.equalsIgnoreCase(code.toString());
    }

    private void checkOwner(CompanyAccess actor,Map<String,Object> task) {
        if (!Set.of("assigned","in_progress").contains(task.get("status"))) throw conflict("请先领取未完成的拣货任务");
        if (!Objects.equals(nullable(task,"assignee_id"),actor.userId()))
            throw new ApiException(403,"FORBIDDEN","只有当前领取人可以操作任务");
    }

    static List<String> serials(List<String> values) {
        if (values==null) return List.of();
        if (values.size()>500 || values.stream().anyMatch(v -> v==null || v.isBlank() || v.length()>100 || v.codePoints().anyMatch(Character::isISOControl))
                || new HashSet<>(values).size()!=values.size()) throw invalid("序列号格式无效、重复或超过每次 500 个");
        return List.copyOf(values);
    }

    static void scope(CompanyAccess actor,StringBuilder sql,List<Object> args,String alias) {
        actor.orgScope().appendOwnerFilter(sql,args,alias);
        warehouseScope(actor,sql,args,alias);
    }

    static void warehouseScope(CompanyAccess actor,StringBuilder sql,List<Object> args,String alias) {
        if (actor.allWarehouses()) return;
        if (actor.warehouseIds().isEmpty()) {
            sql.append(" AND 1=0");
            return;
        }
        sql.append(" AND ").append(alias).append(".warehouse_id IN (")
                .append(String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"))).append(')');
        args.addAll(actor.warehouseIds());
    }

    static void filter(StringBuilder sql,List<Object> args,String field,Object value) {
        if (value!=null) {
            sql.append(" AND ").append(field).append("=?");
            args.add(value);
        }
    }

    static void checkPage(Long cursor,int limit) {
        if (limit<1 || limit>100 || cursor!=null && cursor<=0) throw invalid("游标或分页数量无效");
    }

    static Map<String,Object> pageRows(List<Map<String,Object>> rows,int limit) {
        var result = new LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(limit).toList());
        result.put("nextCursor",rows.size()>limit ? rows.get(limit-1).get("id") : null);
        return result;
    }

    static long value(Map<String,Object> row,String field) { return ((Number)row.get(field)).longValue(); }
    static Long nullable(Map<String,Object> row,String field) { return row.get(field)==null ? null : value(row,field); }
    static BigDecimal decimal(Map<String,Object> row,String field) { return (BigDecimal)row.get(field); }
    static ApiException conflict(String message) { return new ApiException(409,"STATE_CONFLICT",message); }
    static ApiException missing() { return new ApiException(404,"NOT_FOUND","仓储作业或发货单不存在，或无权查看"); }
    static ApiException invalid(String message) { return new ApiException(422,"VALIDATION_FAILED",message); }
}
