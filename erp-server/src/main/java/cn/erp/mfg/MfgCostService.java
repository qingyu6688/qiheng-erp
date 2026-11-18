package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.shared.ProductionCostPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.mfg.MfgCostMath.*;
import static cn.erp.mfg.MfgCostFacts.number;
import static cn.erp.salesfinance.FinanceSupport.version;

@Service
@Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
class MfgCostService implements ProductionCostPort {
    private final JdbcTemplate jdbc;
    private final MfgCostFacts facts;
    private final MfgCostPosting posting;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ObjectMapper json;

    MfgCostService(JdbcTemplate jdbc,MfgCostFacts facts,MfgCostPosting posting,BusinessCommands commands,AuditService audit,ObjectMapper json) {
        this.jdbc=jdbc;this.facts=facts;this.posting=posting;this.commands=commands;this.audit=audit;this.json=json;
    }

    record Expense(String kind,String source,String amount,String accountCode,String allocationBasis) {}
    record Collect(Long version,String wipMethod,List<Expense> expenses) {}
    record Allocation(Long workOrderId,String hours,String progressPct) {}
    record Allocate(Long version,List<Allocation> orders) {}
    record Revision(Long version) {}

    Map<String,Object> list(CompanyAccess actor,Long cursor,int limit) {
        authorize(actor,"mfg_cost:view");
        paging(cursor,limit);
        var rows=jdbc.queryForList("""
                SELECT p.id period_id,p.period,p.start_date,p.end_date,p.status accounting_status,
                    COALESCE(c.status,'open') status,COALESCE(c.version,0) version
                FROM accounting_period p LEFT JOIN mfg_cost_period c ON c.tenant_id=p.tenant_id AND c.company_id=p.company_id AND c.period_id=p.id
                WHERE p.tenant_id=? AND p.company_id=? AND (? IS NULL OR p.id<?) ORDER BY p.id DESC LIMIT ?
                """,actor.tenantId(),actor.companyId(),cursor,cursor,limit+1);
        return page(rows,limit,"period_id");
    }

    Map<String,Object> detail(CompanyAccess actor,long periodId) {
        authorize(actor,"mfg_cost:view");
        var period=facts.period(actor,periodId);
        var current=state(actor,periodId,false);
        var result=new LinkedHashMap<String,Object>();
        result.put("periodId",Long.toString(periodId));result.put("period",period.number());result.put("startDate",period.start());
        result.put("endDate",period.end());result.put("accountingStatus",period.status());result.put("reopenCount",period.reopenCount());
        result.put("status",current.get("status"));result.put("version",current.get("version"));
        result.put("wipMethod",current.get("wip_method"));result.put("hasAllocation",flag(current.get("has_allocation")));
        result.put("expenses",expenses(actor,periodId).stream().map(ApiRows::row).toList());
        result.put("orders",facts.works(actor,period).stream().map(MfgCostService::workView).toList());
        result.put("items",itemRows(actor,periodId).stream().map(ApiRows::row).toList());
        return result;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> collect(CompanyAccess actor,long id,String key,Collect input) {
        return command(actor,id,"collect",key,input,input.version(),(period,current)->{
            mutable(current);
            if (input.wipMethod()==null || !Set.of("materials","equivalent").contains(input.wipMethod())
                    || input.expenses()==null || input.expenses().size()>2) throw invalid("请选择期末在制计算方式及费用池");
            var kinds=new HashSet<String>();var glCodes=new HashSet<String>();
            var inserts=new ArrayList<Object[]>();
            for (var expense : input.expenses()) inserts.add(expense(actor,id,expense,kinds,glCodes));
            delete(actor,id,"mfg_expense_pool");delete(actor,id,"mfg_cost_input");delete(actor,id,"mfg_cost_item");
            jdbc.batchUpdate("""
                    INSERT INTO mfg_expense_pool(id,tenant_id,company_id,period_id,kind,source,subject_code,allocation_basis,amount)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """,inserts);
            jdbc.update("UPDATE mfg_cost_period SET wip_method=?,status='open',has_allocation=0,calculation_hash=NULL,version=version+1 WHERE tenant_id=? AND company_id=? AND period_id=?",
                    input.wipMethod(),actor.tenantId(),actor.companyId(),id);
            return detail(actor,id);
        });
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> allocate(CompanyAccess actor,long id,String key,Allocate input) {
        return command(actor,id,"allocate",key,input,input.version(),(period,current)->{
            mutable(current);
            if (input.orders()==null || input.orders().size()>5000) throw invalid("请核对分摊工单范围");
            var valid=facts.works(actor,period).stream().map(r->r.work().id()).collect(java.util.stream.Collectors.toSet());
            var inserts=new ArrayList<Object[]>();
            for (var row : input.orders()) {
                if (row==null || row.workOrderId()==null || !valid.remove(row.workOrderId())) throw MfgCostFacts.missing();
                var hours=row.hours()==null?ZERO:decimal(row.hours(),6,12,"工时");
                var progress=row.progressPct()==null?null:decimal(row.progressPct(),6,3,"完工程度");
                if (progress!=null && progress.compareTo(BigDecimal.valueOf(100))>0) throw invalid("完工程度不能超过 100%");
                inserts.add(new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,row.workOrderId(),hours,progress});
            }
            delete(actor,id,"mfg_cost_input");delete(actor,id,"mfg_cost_item");
            jdbc.batchUpdate("INSERT INTO mfg_cost_input(id,tenant_id,company_id,period_id,work_order_id,hours,progress_pct) VALUES(?,?,?,?,?,?,?)",inserts);
            jdbc.update("UPDATE mfg_cost_period SET status='open',has_allocation=1,calculation_hash=NULL,version=version+1 WHERE tenant_id=? AND company_id=? AND period_id=?",
                    actor.tenantId(),actor.companyId(),id);
            return detail(actor,id);
        });
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> calculate(CompanyAccess actor,long id,String action,String key,Revision input) {
        return command(actor,id,action,key,input,input.version(),(period,current)->{
            mutable(current);
            var plan=preparePlan(actor,period,current,true);
            delete(actor,id,"mfg_cost_item");
            saveItems(actor,id,plan.orders());
            jdbc.update("UPDATE mfg_cost_period SET status='calculated',calculation_hash=?,version=version+1 WHERE tenant_id=? AND company_id=? AND period_id=?",
                    hash(plan),actor.tenantId(),actor.companyId(),id);
            return detail(actor,id);
        });
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> post(CompanyAccess actor,long id,String key,Revision input) {
        return command(actor,id,"post",key,input,input.version(),(period,current)->{
            if (!"calculated".equals(current.get("status"))) throw invalid("请先计算并核对本期生产成本");
            if (!unposted(actor,period).isEmpty()) throw invalid("本期还有待复核或待过账的生产单据，请先处理");
            var plan=preparePlan(actor,period,current,false);
            if (!hash(plan).equals(current.get("calculation_hash"))) throw invalid("库存、完工或总账费用已发生变化，请重新计算本期成本");
            var postingId=posting.post(actor,period,plan);
            jdbc.update("UPDATE mfg_cost_period SET status='posted',posting_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND period_id=?",
                    postingId,actor.tenantId(),actor.companyId(),id);
            return detail(actor,id);
        });
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    Map<String,Object> reopen(CompanyAccess actor,long id,String key,Revision input) {
        return command(actor,id,"reopen",key,input,input.version(),(period,current)->{
            if (!"posted".equals(current.get("status"))) throw invalid("本期生产成本尚未过账");
            posting.undo(actor,period,number(current,"posting_id"));
            delete(actor,id,"mfg_cost_item");
            jdbc.update("UPDATE mfg_cost_period SET status='open',posting_id=NULL,calculation_hash=NULL,version=version+1 WHERE tenant_id=? AND company_id=? AND period_id=?",
                    actor.tenantId(),actor.companyId(),id);
            return detail(actor,id);
        });
    }

    private Plan preparePlan(CompanyAccess actor,MfgCostFacts.Period period,Map<String,Object> current,boolean refresh) {
        if (!flag(current.get("has_allocation"))) throw invalid("请先确认费用分摊依据");
        earlierCosts(actor,period);
        var work=new LinkedHashMap<Long,Work>();
        facts.works(actor,period).forEach(row->work.put(row.work().id(),row.work()));
        var labor=Map.<Long,BigDecimal>of();var overhead=Map.<Long,BigDecimal>of();
        for (var row : expenses(actor,period.id())) {
            String kind=row.get("kind").toString();
            var amount=(BigDecimal)row.get("amount");
            if ("gl".equals(row.get("source"))) {
                amount=facts.collectGl(actor,period.id(),kind,row.get("account_code").toString());
                if (refresh) jdbc.update("UPDATE mfg_expense_pool SET amount=? WHERE tenant_id=? AND company_id=? AND period_id=? AND kind=?",
                        amount,actor.tenantId(),actor.companyId(),period.id(),kind);
            }
            var portions=split(amount,weights(work,row.get("allocation_basis").toString()));
            if (kind.equals("labor")) labor=portions;else overhead=portions;
        }
        return MfgCostMath.calculate(work,facts.exposures(actor,period),labor,overhead,current.get("wip_method").toString());
    }

    private static Map<Long,BigDecimal> weights(Map<Long,Work> work,String basis) {
        var result=new LinkedHashMap<Long,BigDecimal>();
        work.forEach((id,row)->result.put(id,switch (basis) {
            case "hours" -> row.hours();
            case "quantity" -> row.finished();
            case "material" -> row.material().max(ZERO);
            default -> throw invalid("费用分摊依据无效");
        }));
        return result;
    }

    private void earlierCosts(ActorScope actor,MfgCostFacts.Period period) {
        if (has("""
                SELECT EXISTS(SELECT 1 FROM accounting_period p
                  LEFT JOIN mfg_cost_period c ON c.tenant_id=p.tenant_id AND c.company_id=p.company_id AND c.period_id=p.id
                  WHERE p.tenant_id=? AND p.company_id=? AND p.period<? AND COALESCE(c.status,'open')<>'posted'
                  AND (EXISTS(SELECT 1 FROM mfg_cost_movement m
                      WHERE m.tenant_id=p.tenant_id AND m.company_id=p.company_id AND m.period_id=p.id)
                    OR EXISTS(SELECT 1 FROM mfg_work_order_short_close s
                      WHERE s.tenant_id=p.tenant_id AND s.company_id=p.company_id AND s.period_id=p.id AND s.status='approved')))
                """,actor.tenantId(),actor.companyId(),period.number())) throw invalid("请先完成之前有生产业务的期间成本过账");
    }

    @Override
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireStockPosting(ActorScope actor,long periodId) {
        if ("posted".equals(state(actor,periodId,true).get("status")))
            throw invalid("本会计期间已完成生产成本过账，请按原关账流程重开并冲回成本后办理");
    }

    @Override
    public BigDecimal estimatedExpenseRate(ActorScope actor,long workOrderId,long periodId) {
        var period=facts.period(actor,periodId);
        return jdbc.query("""
                SELECT SUM(i.labor_cost+i.overhead_cost) expense,SUM(i.finished_qty) qty FROM mfg_cost_item i
                JOIN mfg_cost_period c ON c.tenant_id=i.tenant_id AND c.company_id=i.company_id AND c.period_id=i.period_id AND c.status='posted'
                JOIN accounting_period p ON p.tenant_id=i.tenant_id AND p.company_id=i.company_id AND p.id=i.period_id
                JOIN mfg_work_order w ON w.tenant_id=i.tenant_id AND w.company_id=i.company_id AND w.id=i.work_order_id
                JOIN mfg_work_order target ON target.tenant_id=w.tenant_id AND target.company_id=w.company_id AND target.sku_id=w.sku_id AND target.id=?
                WHERE i.tenant_id=? AND i.company_id=? AND p.period<? AND i.finished_qty>0
                GROUP BY p.period ORDER BY p.period DESC LIMIT 1
                """,(r,n)->r.getBigDecimal("expense").divide(r.getBigDecimal("qty"),8,RoundingMode.HALF_UP),
                workOrderId,actor.tenantId(),actor.companyId(),period.number()).stream().findFirst().orElse(ZERO);
    }

    @Override
    public List<Map<String,Object>> closingChecks(ActorScope actor,long periodId) {
        var period=facts.period(actor,periodId);
        var pending=unposted(actor,period);
        long movements=jdbc.queryForObject("SELECT COUNT(*) FROM mfg_cost_movement WHERE tenant_id=? AND company_id=? AND period_id=?",
                Long.class,actor.tenantId(),actor.companyId(),periodId);
        boolean shortClosed=has("""
                SELECT EXISTS(SELECT 1 FROM mfg_work_order_short_close
                WHERE tenant_id=? AND company_id=? AND period_id=? AND status='approved')
                """,actor.tenantId(),actor.companyId(),periodId);
        if (movements==0 && !shortClosed && pending.isEmpty() && "open".equals(state(actor,periodId,false).get("status"))) return List.of();
        var result=new ArrayList<Map<String,Object>>();
        result.add(check("production_unposted_documents",pending.isEmpty(),pending.size(),"生产单据均已处理","本期仍有待处理的生产单据",pending));
        boolean posted="posted".equals(state(actor,periodId,false).get("status"));
        result.add(check("production_cost",posted,posted?0:1,"生产成本已过账","本期生产成本尚未过账",List.of()));
        if (posted) result.add(wipCheck(actor,period));
        long orphans=jdbc.queryForObject("SELECT COUNT(*) FROM mfg_cost_movement WHERE tenant_id=? AND company_id=? AND period_id=? AND work_order_id IS NULL",
                Long.class,actor.tenantId(),actor.companyId(),periodId);
        if (orphans>0) result.add(check("production_source",false,orphans,"生产来源完整","存在缺少工单来源的生产流水",List.of()));
        return List.copyOf(result);
    }

    private List<Map<String,Object>> unposted(ActorScope actor,MfgCostFacts.Period period) {
        return jdbc.queryForList("""
                SELECT 'mfg_issue_doc' document_type,id document_id,document_no,status FROM mfg_issue_doc
                  WHERE tenant_id=? AND company_id=? AND business_date BETWEEN ? AND ? AND status IN('pending','approved')
                UNION ALL SELECT 'mfg_receipt_doc',id,document_no,status FROM mfg_receipt_doc
                  WHERE tenant_id=? AND company_id=? AND business_date BETWEEN ? AND ? AND status IN('pending','approved') LIMIT 50
                """,actor.tenantId(),actor.companyId(),period.start(),period.end(),actor.tenantId(),actor.companyId(),period.start(),period.end())
                .stream().map(ApiRows::row).toList();
    }

    private Map<String,Object> wipCheck(ActorScope actor,MfgCostFacts.Period period) {
        var book=jdbc.queryForObject("""
                SELECT COALESCE(SUM(IF(l.direction='D',l.amount,-l.amount)),0) FROM journal_line l
                JOIN journal j ON j.tenant_id=l.tenant_id AND j.company_id=l.company_id AND j.id=l.journal_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.account_code='WIP' AND j.business_date<=?
                """,BigDecimal.class,actor.tenantId(),actor.companyId(),period.end());
        var orders=jdbc.queryForObject("SELECT COALESCE(SUM(wip_end),0) FROM mfg_cost_item WHERE tenant_id=? AND company_id=? AND period_id=?",
                BigDecimal.class,actor.tenantId(),actor.companyId(),period.id());
        boolean passed=book.compareTo(orders)==0;
        return check("production_wip",passed,passed?0:1,"在制品账本与工单在制余额一致","在制品账本与工单在制余额存在差异",List.of());
    }

    private static Map<String,Object> check(String name,boolean pass,long count,String yes,String no,List<Map<String,Object>> docs) {
        return Map.of("check",name,"passed",pass,"count",count,"message",pass?yes:no,"documents",docs);
    }

    private Object[] expense(ActorScope actor,long id,Expense row,Set<String> kinds,Set<String> glCodes) {
        if (row==null || row.kind()==null || !Set.of("labor","overhead").contains(row.kind()) || !kinds.add(row.kind())
                || row.allocationBasis()==null || !Set.of("hours","quantity","material").contains(row.allocationBasis())) throw invalid("费用类型或分摊依据无效");
        var subject=row.accountCode();BigDecimal amount;
        if ("manual".equals(row.source()) && subject==null) amount=decimal(row.amount(),2,22,"费用金额");
        else if ("gl".equals(row.source()) && subject!=null && subject.matches("[0-9A-Za-z_.-]{1,32}") && glCodes.add(subject))
            amount=facts.collectGl(actor,id,row.kind(),subject);
        else throw invalid("请选择手工金额或不同的总账生产费用科目");
        return new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,row.kind(),row.source(),subject,row.allocationBasis(),amount};
    }

    private void saveItems(ActorScope actor,long id,List<OrderCost> items) {
        jdbc.batchUpdate("""
                INSERT INTO mfg_cost_item(id,tenant_id,company_id,period_id,work_order_id,finished_qty,material_cost,rolled_material,
                   labor_cost,overhead_cost,wip_begin,wip_end,wip_end_material,finished_cost,estimated_cost,variance,inventory_variance,
                   issued_variance,rolled_variance,short_close_expense)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,items.stream().map(i->new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,i.workOrderId(),i.finishedQty(),i.materialCost(),
                i.rolledMaterial(),i.laborCost(),i.overheadCost(),i.wipBegin(),i.wipEnd(),i.wipEndMaterial(),i.finishedCost(),i.estimatedCost(),
                i.variance(),i.inventoryVariance(),i.issuedVariance(),i.rolledVariance(),Objects.requireNonNullElse(i.shortCloseExpense(),ZERO)}).toList());
    }

    private List<Map<String,Object>> itemRows(ActorScope actor,long id) {
        return jdbc.queryForList("""
                SELECT i.*,w.document_no,w.sku_id FROM mfg_cost_item i JOIN mfg_work_order w ON w.tenant_id=i.tenant_id
                   AND w.company_id=i.company_id AND w.id=i.work_order_id
                WHERE i.tenant_id=? AND i.company_id=? AND i.period_id=? ORDER BY i.work_order_id
                """,actor.tenantId(),actor.companyId(),id);
    }

    private List<Map<String,Object>> expenses(ActorScope actor,long id) {
        return jdbc.queryForList("""
                SELECT kind,source,subject_code account_code,allocation_basis,amount FROM mfg_expense_pool
                WHERE tenant_id=? AND company_id=? AND period_id=? ORDER BY kind
                """,actor.tenantId(),actor.companyId(),id);
    }

    private Map<String,Object> state(ActorScope actor,long id,boolean lock) {
        return jdbc.queryForList("SELECT * FROM mfg_cost_period WHERE tenant_id=? AND company_id=? AND period_id=?"+(lock?" FOR SHARE":""),
                actor.tenantId(),actor.companyId(),id).stream().findFirst()
                .orElse(Map.of("status","open","version",0L,"wip_method","materials","has_allocation",false));
    }

    private record Locked(MfgCostFacts.Period period,Map<String,Object> current) {}
    private Locked lock(ActorScope actor,long id) {
        // 与库存、总账记账及原关账使用同一会计期间屏障。
        jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? ORDER BY period FOR UPDATE",Long.class,actor.tenantId(),actor.companyId());
        var period=facts.period(actor,id);
        if (!period.status().equals("open")) throw new ApiException(422,"PERIOD_CLOSED","会计期间已经关闭");
        if (period.end().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw invalid("请在会计期间结束后办理生产成本");
        return new Locked(period,state(actor,id,false));
    }

    private interface Action {Map<String,Object> run(MfgCostFacts.Period period,Map<String,Object> current);}
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> command(CompanyAccess actor,long id,String action,String key,Object input,Long expected,Action task) {
        authorize(actor,Set.of("post","reopen").contains(action)?"mfg_cost:post":"mfg_cost:manage");
        facts.period(actor,id);
        return commands.execute(actor,"mfg_cost."+action,key,List.of(id,input),200,()->{
            var record=lock(actor,id);
            var current=record.current();
            version(current,expected);
            if (!current.containsKey("id")) jdbc.update("INSERT INTO mfg_cost_period(id,tenant_id,company_id,period_id) VALUES(?,?,?,?)",
                    Ids.next(),actor.tenantId(),actor.companyId(),id);
            var result=task.run(record.period(),current);
            audit.record(actor,"mfg_cost."+action,"mfg_cost_period",id,Map.of("status",current.get("status"),"version",current.get("version")),
                    Map.of("status",result.get("status"),"version",result.get("version")));
            return result;
        },(Class)Map.class);
    }

    private void delete(ActorScope actor,long id,String table) {
        jdbc.update("DELETE FROM "+table+" WHERE tenant_id=? AND company_id=? AND period_id=?",actor.tenantId(),actor.companyId(),id);
    }

    private String hash(Plan plan) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(plan).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) {throw new IllegalStateException("缺少生产成本校验算法",error);}
    }

    private boolean has(String sql,Object... args) {return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
    private static void mutable(Map<String,Object> current) {if ("posted".equals(current.get("status"))) throw invalid("已过账的生产成本不能重算");}
    private static boolean flag(Object value) {return value instanceof Boolean bool?bool:((Number)value).intValue()!=0;}

    private static BigDecimal decimal(String value,int scale,int digits,String label) {
        if (value==null || value.length()>32 || !value.matches("[0-9]+(?:\\.[0-9]{1,"+scale+"})?")) throw invalid(label+"的格式无效");
        var number=new BigDecimal(value).setScale(scale);
        if (number.precision()-scale>digits) throw invalid(label+"超出允许范围");
        return number;
    }

    static void paging(Long cursor,int limit) {
        if (cursor!=null && cursor<=0 || limit<1 || limit>100) throw invalid("分页参数无效");
    }

    static Map<String,Object> page(List<Map<String,Object>> rows,int limit,String cursorKey) {
        var selected=rows.subList(0,Math.min(rows.size(),limit));
        var result=new LinkedHashMap<String,Object>();
        result.put("items",selected.stream().map(ApiRows::row).toList());
        result.put("nextCursor",rows.size()>limit?selected.getLast().get(cursorKey).toString():null);
        return result;
    }

    private static Map<String,Object> workView(MfgCostFacts.WorkRow data) {
        var row=data.work();var result=new LinkedHashMap<String,Object>();
        result.put("workOrderId",Long.toString(row.id()));result.put("documentNo",data.documentNo());
        result.put("skuId",Long.toString(data.sku()));result.put("skuCode",data.skuCode());result.put("planQty",row.plan().toPlainString());
        result.put("periodCompletedQty",row.finished().toPlainString());result.put("hours",row.hours().toPlainString());
        result.put("progressPct",row.progress()==null?null:row.progress().toPlainString());
        if(row.shortCloseDisposition()!=null) result.put("shortCloseDisposition",row.shortCloseDisposition());
        return result;
    }

    private static void authorize(CompanyAccess actor,String permission) {
        if (!actor.allWarehouses() || !actor.orgScope().allDepartments() || !actor.hasPermission("cost:view")
                || !actor.hasPermission("mfg_cost:view") || !actor.hasPermission(permission))
            throw new ApiException(403,"FORBIDDEN","生产成本归集与结算需要整家公司数据范围及对应权限");
    }
}
