package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.LocationMoveService;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.wms.WmsScopes.conflict;
import static cn.erp.wms.WmsScopes.invalid;
import static cn.erp.wms.WmsScopes.missing;
import static cn.erp.wms.WmsScopes.number;

@Service
class WmsMoves {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String HEAD = """
            SELECT t.id,t.no,t.type,t.warehouse_id,t.assignee_id,t.version,d.remark,d.status,d.posted_at,d.rule_id
            FROM wms_move_doc d JOIN wms_task t ON t.tenant_id=d.tenant_id AND t.company_id=d.company_id AND t.id=d.id
            WHERE t.tenant_id=? AND t.company_id=?
            """;
    private static final TypeReference<List<String>> SERIALS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final WmsScopes scopes;
    private final FinanceSupport finance;
    private final InventoryService inventory;
    private final LocationMoveService stock;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ObjectMapper json;

    WmsMoves(JdbcTemplate jdbc,WmsScopes scopes,FinanceSupport finance,InventoryService inventory,LocationMoveService stock,
             BusinessCommands commands,AuditService audit,ObjectMapper json) {
        this.jdbc=jdbc;
        this.scopes=scopes;
        this.finance=finance;
        this.inventory=inventory;
        this.stock=stock;
        this.commands=commands;
        this.audit=audit;
        this.json=json;
    }

    record Line(Long skuId,Long batchId,Long fromLocationId,Long toLocationId,String qty,List<String> serialNos) {}

    record Draft(Long warehouseId,String remark,Long version,List<Line> lines) {}

    @Transactional(readOnly = true)
    Map<String,Object> page(CompanyAccess actor,Long warehouse,String type,String status,Long cursor,int limit) {
        WmsScopes.limit(cursor,limit);
        if (type!=null && !Set.of("move","replenish").contains(type)) throw invalid("移库单类型无效");
        if (status!=null && !Set.of("draft","posted","cancelled").contains(status)) throw invalid("移库单状态无效");
        var sql=new StringBuilder(HEAD);
        var values=scopes.params(actor);
        scopes.taskFilter(actor,"t",sql,values);
        append(sql,values,"t.warehouse_id",warehouse);
        append(sql,values,"t.type",type);
        append(sql,values,"d.status",status);
        if (cursor!=null) {
            sql.append(" AND t.id<?");
            values.add(cursor);
        }
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        values.add(limit+1);
        return WmsScopes.page(jdbc.queryForList(sql.toString(),values.toArray()),limit);
    }

    @Transactional(readOnly = true)
    Map<String,Object> detail(CompanyAccess actor,long id) {
        var task=ApiRows.row(head(actor,id,false));
        var lines=lines(actor,id).stream().map(raw->{
            var line=ApiRows.row(raw);
            line.put("serialNos",json.readValue(raw.get("serial_nos").toString(),SERIALS));
            return line;
        }).toList();
        task.put("lines",lines);
        return task;
    }

    @Transactional
    String create(CompanyAccess actor,String key,Draft input) {
        validateDraft(actor,input);
        return commands.execute(actor,"wms_move.create",key,input,201,()->{
            long id=scopes.newTask(actor,input.warehouseId(),"move","wms_move","MV-");
            insertDocument(actor,id,input.warehouseId(),null,remark(input.remark()),input.lines());
            audit.record(actor,"wms_move.create","wms_move_doc",id,null,Map.of("warehouseId",input.warehouseId()));
            return Long.toString(id);
        },String.class);
    }

    @Transactional
    String edit(CompanyAccess actor,long id,String key,Draft input) {
        head(actor,id,false);
        return commands.execute(actor,"wms_move.edit",key,List.of(id,input),200,()->{
            var row=head(actor,id,true);
            draft(row,input.version());
            requireManual(row);
            if (!Objects.equals(input.warehouseId(),number(row,"warehouse_id"))) throw invalid("移库单仓库创建后不可更换");
            requireActor(actor,row);
            validateDraft(actor,input);
            jdbc.update("UPDATE wms_move_doc SET remark=? WHERE tenant_id=? AND company_id=? AND id=?",
                    remark(input.remark()),actor.tenantId(),actor.companyId(),id);
            jdbc.update("DELETE FROM wms_move_line WHERE tenant_id=? AND company_id=? AND move_id=?",actor.tenantId(),actor.companyId(),id);
            writeLines(actor,id,input.warehouseId(),input.lines());
            jdbc.update("UPDATE wms_task SET version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"wms_move.edit","wms_move_doc",id,null,Map.of("version",input.version()));
            return Long.toString(id);
        },String.class);
    }

    @Transactional
    String cancel(CompanyAccess actor,long id,String key,WmsScopes.Revision input) {
        head(actor,id,false);
        return commands.execute(actor,"wms_move.cancel",key,List.of(id,input),200,()->{
            var row=head(actor,id,true);
            draft(row,input.version());
            requireActor(actor,row);
            cancelLocked(actor,id);
            audit.record(actor,"wms_move.cancel","wms_move_doc",id,null,null);
            return Long.toString(id);
        },String.class);
    }

    void cancelLocked(CompanyAccess actor,long id) {
        jdbc.update("UPDATE wms_move_doc SET status='cancelled' WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(),actor.companyId(),id);
        jdbc.update("UPDATE wms_task SET status='cancelled',version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(),actor.companyId(),id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    String post(CompanyAccess actor,long id,String key,WmsScopes.Revision input,boolean replenishOnly) {
        var visible=head(actor,id,false);
        boolean replenish="replenish".equals(visible.get("type"));
        if (replenishOnly && !replenish) throw missing();
        return commands.execute(actor,"wms_move.post",key,List.of(id,input),200,()->{
            var day=LocalDate.now(BUSINESS_ZONE);
            long period=finance.period(actor,day);
            Map<String,Object> rule=replenish ? lockRule(actor,visible) : null;
            var row=head(actor,id,true);
            draft(row,input.version());
            requireActor(actor,row);
            apply(actor,row,rule,day,period);
            audit.record(actor,"wms_move.post","wms_move_doc",id,null,Map.of("status","posted"));
            return Long.toString(id);
        },String.class);
    }

    private void apply(CompanyAccess actor,Map<String,Object> row,Map<String,Object> rule,LocalDate day,long period) {
        long id=number(row,"id"), warehouse=number(row,"warehouse_id");
        var lines=lines(actor,id);
        inventory.lockKeys(actor,lines.stream().map(line->new Key(warehouse,number(line,"sku_id"))).distinct().toList());
        if (rule!=null) verifyReplenishment(actor,row,rule,lines);
        for (var line:lines) {
            stock.manualMove(actor,warehouse,number(line,"sku_id"),nullable(line,"batch_id"),number(line,"from_location_id"),
                    number(line,"to_location_id"),(BigDecimal)line.get("qty"),json.readValue(line.get("serial_nos").toString(),SERIALS),
                    id,number(line,"id"),day,period,rule!=null);
        }
        jdbc.update("UPDATE wms_move_doc SET status='posted',posted_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(),actor.companyId(),id);
        jdbc.update("""
                UPDATE wms_task SET status='done',assignee_id=?,started_at=COALESCE(started_at,UTC_TIMESTAMP(3)),
                  finished_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """,actor.userId(),actor.tenantId(),actor.companyId(),id);
    }

    long generate(CompanyAccess actor,long warehouse,Map<String,Object> rule,List<Line> lines) {
        long id=Ids.next();
        scopes.insertTask(actor,id,warehouse,"replenish","wms_replenishment",id,"RP-",
                number(rule,"owner_user_id"),number(rule,"owner_department_id"));
        insertDocument(actor,id,warehouse,number(rule,"id"),"拣货位低于下限",lines);
        return id;
    }

    private Map<String,Object> lockRule(CompanyAccess actor,Map<String,Object> row) {
        return jdbc.queryForList("""
                SELECT * FROM wms_location_rule WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND id=? FOR SHARE
                """,actor.tenantId(),actor.companyId(),row.get("warehouse_id"),row.get("rule_id")).stream()
                .findFirst().orElseThrow(WmsScopes::missing);
    }

    private void verifyReplenishment(CompanyAccess actor,Map<String,Object> row,Map<String,Object> rule,List<Map<String,Object>> lines) {
        if (rule.get("min_qty")==null || rule.get("fixed_location_id")==null
                || lines.stream().anyMatch(line->!line.get("to_location_id").equals(rule.get("fixed_location_id"))
                    || !line.get("sku_id").equals(rule.get("sku_id"))))
            throw conflict("补货规则已变化，请取消草稿后重新生成");
        var available=availableAt(actor,number(row,"warehouse_id"),number(rule,"sku_id"),number(rule,"fixed_location_id"));
        var total=lines.stream().map(line->(BigDecimal)line.get("qty")).reduce(BigDecimal.ZERO,BigDecimal::add);
        if (available.add(total).compareTo((BigDecimal)rule.get("max_qty"))>0)
            throw conflict("拣货位库存已变化，补货量超过当前上限");
    }

    BigDecimal availableAt(CompanyAccess actor,long warehouse,long sku,long location) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(GREATEST(i.on_hand_qty-i.blocked_qty-COALESCE((
                    SELECT SUM(p.qty) FROM inv_shipment_pick p
                    WHERE p.tenant_id=i.tenant_id AND p.company_id=i.company_id AND p.warehouse_id=i.warehouse_id
                      AND p.sku_id=i.sku_id AND p.location_key=i.location_key AND p.lot_key=i.lot_key
                ),0),0)),0)
                FROM inventory_balance i LEFT JOIN inv_batch b ON b.tenant_id=i.tenant_id AND b.company_id=i.company_id
                  AND b.sku_id=i.sku_id AND b.id=CAST(i.lot_key AS UNSIGNED)
                WHERE i.tenant_id=? AND i.company_id=? AND i.warehouse_id=? AND i.sku_id=? AND i.location_key=?
                  AND (i.lot_key='' OR (b.status='normal' AND (b.expiry_date IS NULL OR b.expiry_date>=?)))
                """,BigDecimal.class,actor.tenantId(),actor.companyId(),warehouse,sku,Long.toString(location),LocalDate.now(BUSINESS_ZONE));
    }

    private void insertDocument(CompanyAccess actor,long id,long warehouse,Long rule,String note,List<Line> lines) {
        jdbc.update("""
                INSERT INTO wms_move_doc(id,tenant_id,company_id,warehouse_id,rule_id,remark) VALUES(?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),warehouse,rule,note);
        writeLines(actor,id,warehouse,lines);
    }

    private void writeLines(CompanyAccess actor,long id,long warehouse,List<Line> lines) {
        for(var line:lines) {
            jdbc.update("""
                    INSERT INTO wms_move_line(id,tenant_id,company_id,warehouse_id,move_id,sku_id,batch_id,from_location_id,
                      to_location_id,qty,serial_nos) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """,Ids.next(),actor.tenantId(),actor.companyId(),warehouse,id,line.skuId(),line.batchId(),line.fromLocationId(),
                    line.toLocationId(),FinanceSupport.quantity(line.qty()),json.writeValueAsString(serials(line)));
        }
    }

    private void validateDraft(CompanyAccess actor,Draft input) {
        if (input.warehouseId()==null || input.lines()==null || input.lines().isEmpty() || input.lines().size()>200
                || input.lines().stream().anyMatch(Objects::isNull)) throw invalid("请选择仓库和 1 到 200 条移库行");
        remark(input.remark());
        long warehouse=input.warehouseId();
        scopes.warehouse(actor,warehouse,true);
        for (var line:input.lines()) validateLine(actor,warehouse,line);
    }

    private void validateLine(CompanyAccess actor,long warehouse,Line line) {
        if (line.skuId()==null || line.fromLocationId()==null || line.toLocationId()==null
                || line.fromLocationId().equals(line.toLocationId())) throw invalid("请选择商品及两个不同的库位");
        var qty=FinanceSupport.quantity(line.qty());
        var sku=jdbc.queryForList("SELECT batch_managed,serial_managed FROM sku WHERE tenant_id=? AND id=? AND status='active'",
                actor.tenantId(),line.skuId()).stream().findFirst().orElseThrow(WmsScopes::missing);
        var locations=jdbc.queryForObject("""
                SELECT COUNT(*) FROM wms_location l JOIN wms_zone z ON z.tenant_id=l.tenant_id AND z.company_id=l.company_id
                  AND z.warehouse_id=l.warehouse_id AND z.id=l.zone_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.id IN (?,?)
                  AND l.status='active' AND z.status='active' AND (l.id<>? OR z.type<>'quarantine')
                """,Long.class,actor.tenantId(),actor.companyId(),warehouse,line.fromLocationId(),line.toLocationId(),line.toLocationId());
        if (locations!=2) throw missing();
        if (flag(sku.get("batch_managed")) != (line.batchId()!=null)) throw invalid("请选择符合商品批次管理的批次");
        if (line.batchId()!=null && jdbc.queryForList("""
                SELECT id FROM inv_batch WHERE tenant_id=? AND company_id=? AND sku_id=? AND id=?
                """,Long.class,actor.tenantId(),actor.companyId(),line.skuId(),line.batchId()).isEmpty()) throw missing();
        var serials=serials(line);
        if (flag(sku.get("serial_managed")) && qty.compareTo(BigDecimal.valueOf(serials.size()))!=0
                || !flag(sku.get("serial_managed")) && !serials.isEmpty())
            throw invalid("序列号数量须与商品的移库数量和序列号管理方式一致");
    }

    private Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var sql=new StringBuilder(HEAD).append(" AND t.id=?");
        var values=scopes.params(actor);
        values.add(id);
        scopes.taskFilter(actor,"t",sql,values);
        if(lock) sql.append(" FOR UPDATE OF t,d");
        return jdbc.queryForList(sql.toString(),values.toArray()).stream().findFirst().orElseThrow(WmsScopes::missing);
    }

    private List<Map<String,Object>> lines(CompanyAccess actor,long id) {
        return jdbc.queryForList("""
                SELECT ml.id,ml.sku_id,s.code sku_code,s.name sku_name,ml.batch_id,b.batch_no,
                       ml.from_location_id,f.code from_location_code,ml.to_location_id,t.code to_location_code,ml.qty,ml.serial_nos
                FROM wms_move_line ml JOIN sku s ON s.tenant_id=ml.tenant_id AND s.id=ml.sku_id
                LEFT JOIN inv_batch b ON b.tenant_id=ml.tenant_id AND b.company_id=ml.company_id AND b.id=ml.batch_id
                JOIN wms_location f ON f.tenant_id=ml.tenant_id AND f.company_id=ml.company_id AND f.warehouse_id=ml.warehouse_id AND f.id=ml.from_location_id
                JOIN wms_location t ON t.tenant_id=ml.tenant_id AND t.company_id=ml.company_id AND t.warehouse_id=ml.warehouse_id AND t.id=ml.to_location_id
                WHERE ml.tenant_id=? AND ml.company_id=? AND ml.move_id=?
                ORDER BY ml.sku_id,LEAST(ml.from_location_id,ml.to_location_id),GREATEST(ml.from_location_id,ml.to_location_id),ml.id
                """,actor.tenantId(),actor.companyId(),id);
    }

    private void draft(Map<String,Object> row,Long version) {
        FinanceSupport.version(row,version);
        if(!"draft".equals(row.get("status"))) throw conflict("只有待执行的移库单可以操作");
    }

    private void requireManual(Map<String,Object> row) {
        if (!"move".equals(row.get("type"))) throw conflict("补货作业由拣货规则生成，请取消后重新生成");
    }

    private void requireActor(CompanyAccess actor,Map<String,Object> row) {
        if (row.get("assignee_id")!=null && number(row,"assignee_id")!=actor.userId()) throw WmsScopes.forbidden();
    }

    private String remark(String text) {
        String value=Objects.toString(text,"").strip();
        if (value.length()>300) throw invalid("移库说明最多 300 个字符");
        return value;
    }

    private List<String> serials(Line line) {
        var values=line.serialNos()==null?List.<String>of():line.serialNos();
        if(values.size()>500 || values.stream().anyMatch(s->s==null || s.isBlank() || s.length()>100)
                || values.stream().distinct().count()!=values.size()) throw invalid("请填写不重复的有效序列号，每行最多 500 个");
        return values;
    }

    static boolean flag(Object value) { return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value)); }

    static Long nullable(Map<String,Object> row,String field) { return row.get(field)==null ? null : number(row,field); }

    private static void append(StringBuilder sql,List<Object> values,String column,Object value) {
        if (value==null) return;
        sql.append(" AND ").append(column).append("=?");
        values.add(value);
    }
}
