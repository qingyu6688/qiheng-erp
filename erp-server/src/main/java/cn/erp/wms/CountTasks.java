package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.stockcount.StockCountModels;
import cn.erp.stockcount.StockCountService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.wms.WmsScopes.conflict;
import static cn.erp.wms.WmsScopes.forbidden;
import static cn.erp.wms.WmsScopes.invalid;
import static cn.erp.wms.WmsScopes.number;

@Service
class CountTasks {
    private static final String HEAD="""
            SELECT t.id,t.no,t.type,t.warehouse_id,t.status,t.assignee_id,t.version,g.location_id,g.count_id,
                   l.code location_code,c.count_no
            FROM wms_task t JOIN wms_count_group g ON g.tenant_id=t.tenant_id AND g.company_id=t.company_id AND g.task_id=t.id
            JOIN stock_count c ON c.tenant_id=g.tenant_id AND c.company_id=g.company_id AND c.id=g.count_id
            JOIN wms_location l ON l.tenant_id=g.tenant_id AND l.company_id=g.company_id AND l.warehouse_id=g.warehouse_id AND l.id=g.location_id
            WHERE t.tenant_id=? AND t.company_id=? AND t.type='count'
            """;
    private final JdbcTemplate jdbc;
    private final WmsScopes scopes;
    private final StockCountService counts;
    private final BusinessCommands commands;
    private final AuditService audit;

    CountTasks(JdbcTemplate jdbc,WmsScopes scopes,StockCountService counts,BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc;
        this.scopes=scopes;
        this.counts=counts;
        this.commands=commands;
        this.audit=audit;
    }

    record Confirmation(Long version,String locationCode,List<StockCountModels.Count> lines) {}

    @Transactional(readOnly=true)
    Map<String,Object> page(CompanyAccess actor,Long warehouse,String status,Long cursor,int limit) {
        WmsScopes.limit(cursor,limit);
        if(status!=null && !Set.of("created","assigned","done","cancelled").contains(status)) throw invalid("盘点作业状态无效");
        var sql=new StringBuilder(HEAD);
        var values=scopes.params(actor);
        scopes.taskFilter(actor,"t",sql,values);
        if(warehouse!=null) {sql.append(" AND t.warehouse_id=?");values.add(warehouse);}
        if(status!=null) {sql.append(" AND t.status=?");values.add(status);}
        if(cursor!=null) {sql.append(" AND t.id<?");values.add(cursor);}
        sql.append(" ORDER BY t.id DESC LIMIT ?");
        values.add(limit+1);
        return WmsScopes.page(jdbc.queryForList(sql.toString(),values.toArray()),limit);
    }

    @Transactional(readOnly=true)
    Map<String,Object> detail(CompanyAccess actor,long id) {
        var row=head(actor,id,false);
        var result=ApiRows.row(row);
        var lines=jdbc.queryForList("""
                SELECT sc.id,sc.sku_id,s.code sku_code,s.name sku_name,sc.batch_id,b.batch_no,sc.book_qty,sc.counted_qty,
                       s.serial_managed
                FROM stock_count_line sc JOIN sku s ON s.tenant_id=sc.tenant_id AND s.id=sc.sku_id
                LEFT JOIN inv_batch b ON b.tenant_id=sc.tenant_id AND b.company_id=sc.company_id AND b.id=sc.batch_id
                WHERE sc.tenant_id=? AND sc.company_id=? AND sc.count_id=? AND sc.location_key=? ORDER BY sc.id
                """,actor.tenantId(),actor.companyId(),row.get("count_id"),row.get("location_id").toString());
        result.put("lines",lines.stream().map(ApiRows::row).toList());
        return result;
    }

    @Transactional
    String claim(CompanyAccess actor,long id,String key,WmsScopes.Revision input) {
        var snapshot=head(actor,id,false);
        return commands.execute(actor,"wms_count.claim",key,List.of(id,input),200,()->{
            lockCounting(actor,number(snapshot,"count_id"));
            var row=head(actor,id,true);
            FinanceSupport.version(row,input.version());
            if(!"created".equals(row.get("status"))) throw conflict("盘点作业已领取或结束");
            jdbc.update("""
                    UPDATE wms_task SET status='assigned',assignee_id=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.userId(),actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"wms_count.claim","wms_task",id,null,Map.of("assigneeId",Long.toString(actor.userId())));
            return Long.toString(id);
        },String.class);
    }

    @Transactional
    String confirm(CompanyAccess actor,long id,String key,Confirmation input) {
        var snapshot=head(actor,id,false);
        String location=input.locationCode()==null?"":input.locationCode().strip();
        if(location.startsWith("L:")) location=location.substring(2);
        if(input.lines()==null || input.lines().isEmpty() || input.lines().size()>200 || location.isBlank() || location.length()>48)
            throw invalid("请扫描库位并录入全部实盘行");
        final String scanned=location;
        return commands.execute(actor,"wms_count.confirm",key,List.of(id,input),200,()->{
            lockCounting(actor,number(snapshot,"count_id"));
            var row=head(actor,id,true);
            FinanceSupport.version(row,input.version());
            if(!"assigned".equals(row.get("status"))) throw conflict("请先领取盘点作业");
            if(row.get("assignee_id")==null || number(row,"assignee_id")!=actor.userId()) throw forbidden();
            if(!scanned.equalsIgnoreCase(row.get("location_code").toString())) throw invalid("扫码库位与盘点作业不一致");
            counts.countLocation(actor,number(row,"count_id"),number(row,"location_id"),input.lines());
            jdbc.update("""
                    UPDATE wms_task SET status='done',started_at=COALESCE(started_at,UTC_TIMESTAMP(3)),
                       finished_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"wms_count.confirm","wms_task",id,null,Map.of("status","done"));
            return Long.toString(id);
        },String.class);
    }

    private Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var sql=new StringBuilder(HEAD).append(" AND t.id=?");
        var values=scopes.params(actor);
        values.add(id);
        scopes.taskFilter(actor,"t",sql,values);
        if(lock) sql.append(" FOR UPDATE OF t");
        return jdbc.queryForList(sql.toString(),values.toArray()).stream().findFirst().orElseThrow(WmsScopes::missing);
    }

    private void lockCounting(CompanyAccess actor,long count) {
        var rows=jdbc.queryForList("""
                SELECT id FROM stock_count WHERE tenant_id=? AND company_id=? AND id=? AND status='counting' FOR UPDATE
                """,Long.class,actor.tenantId(),actor.companyId(),count);
        if(rows.isEmpty()) throw conflict("来源单据未处于实盘阶段");
    }
}
