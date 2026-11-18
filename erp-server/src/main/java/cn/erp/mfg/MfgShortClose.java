package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.shared.ProductionCostPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.mfg.MfgPlanning.*;
import static cn.erp.mfg.MfgPlanningModels.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
class MfgShortClose {
    private static final String RESOURCE="mfg_work_order";
    private final JdbcTemplate jdbc;
    private final MfgOrders orders;
    private final FinanceSupport finance;
    private final ProductionCostPort costs;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ObjectMapper json;

    MfgShortClose(JdbcTemplate jdbc,MfgOrders orders,FinanceSupport finance,ProductionCostPort costs,
                  BusinessCommands commands,AuditService audit,ObjectMapper json) {
        this.jdbc=jdbc;this.orders=orders;this.finance=finance;this.costs=costs;
        this.commands=commands;this.audit=audit;this.json=json;
    }

    Map<String,Object> request(CompanyAccess actor,long id,String key,ShortCloseDraft input) {
        authorize(actor,"short_close");
        orders.header(actor,id,false);
        String reason=text(input.reason(),500,"短交原因",true);
        String disposition=input.costDisposition();
        if(disposition==null || !Set.of("expense","allocate").contains(disposition))
            throw invalid("请选择剩余在制成本的处理方式");
        return execute(actor,id,"short_close",key,input,()->{
            var period=period(actor,input.businessDate());
            var order=orders.header(actor,id,true);
            MfgBomService.checkRevision(order,input.version());
            validateOrder(actor,id,order,input.businessDate(),period,disposition);
            var prior=orders.shortClose(actor,id,true).orElse(null);
            var material=materials(actor,id,input.materials());
            long version=number(order,"version")+1;
            jdbc.update("""
                    INSERT INTO mfg_work_order_short_close(id,tenant_id,company_id,work_order_id,period_id,order_version,
                        business_date,reason,cost_disposition,materials,requested_by) VALUES(?,?,?,?,?,?,?,?,?,?,?) AS incoming
                    ON DUPLICATE KEY UPDATE period_id=incoming.period_id,order_version=incoming.order_version,
                        business_date=incoming.business_date,reason=incoming.reason,cost_disposition=incoming.cost_disposition,
                        materials=incoming.materials,requested_by=incoming.requested_by
                    """,Ids.next(),actor.tenantId(),actor.companyId(),id,period,version,input.businessDate(),reason,
                    disposition,json.writeValueAsString(material),actor.userId());
            jdbc.update("UPDATE mfg_work_order SET version=?,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                    version,actor.userId(),actor.tenantId(),actor.companyId(),id);
            audit.record(actor,RESOURCE+".short_close",RESOURCE,id,prior,orders.detail(actor,id).get("shortClose"));
        });
    }

    Map<String,Object> approve(CompanyAccess actor,long id,String key,Revision input) {
        authorize(actor,"approve_short_close");
        orders.header(actor,id,false);
        return execute(actor,id,"approve_short_close",key,input,()->{
            var snapshot=proposal(actor,id,false);
            var date=date(snapshot,"business_date");
            long period=period(actor,date);
            var order=orders.header(actor,id,true);
            var current=proposal(actor,id,true);
            MfgBomService.checkRevision(order,input.version());
            if(!"pending".equals(current.get("status")) || !date.equals(date(current,"business_date")) || period!=number(current,"period_id")
                    || number(order,"version")!=number(current,"order_version"))
                throw conflict("短交申请或工单已变化，请刷新并重新申请");
            if(actor.userId()==number(current,"requested_by"))
                throw new ApiException(403,"FORBIDDEN","短交申请须由另一位有权用户复核");
            validateOrder(actor,id,order,date,period,current.get("cost_disposition").toString());
            materials(actor,id,json.readValue(current.get("materials").toString(),MfgOrders.MATERIAL_DISPOSITIONS));
            jdbc.update("""
                    UPDATE mfg_work_order_short_close SET status='approved',reviewed_by=?,reviewed_at=UTC_TIMESTAMP(3)
                    WHERE tenant_id=? AND company_id=? AND work_order_id=?
                    """,actor.userId(),actor.tenantId(),actor.companyId(),id);
            jdbc.update("""
                    UPDATE mfg_work_order SET status='closed',closed_at=UTC_TIMESTAMP(3),updated_by=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.userId(),actor.tenantId(),actor.companyId(),id);
            audit.record(actor,RESOURCE+".approve_short_close",RESOURCE,id,current,orders.detail(actor,id).get("shortClose"));
        });
    }

    private Map<String,Object> execute(CompanyAccess actor,long id,String action,String key,Object input,Runnable mutation) {
        commands.execute(actor,RESOURCE+"."+action,key,List.of(id,input),200,()->{
            mutation.run();
            // 请求结果只保存 ID，重试读取时重新检查工单范围和当前权限。
            return Long.toString(id);
        },String.class);
        return orders.detail(actor,id);
    }

    private long period(CompanyAccess actor,LocalDate day) {
        long period=finance.period(actor,day);
        costs.requireStockPosting(actor,period);
        return period;
    }

    private Map<String,Object> proposal(CompanyAccess actor,long id,boolean lock) {
        return orders.shortClose(actor,id,lock).orElseThrow(()->conflict("工单还没有短交申请"));
    }

    private void validateOrder(CompanyAccess actor,long id,Map<String,Object> order,LocalDate day,long period,String disposition) {
        if(!Set.of("released","in_progress").contains(order.get("status"))
                || decimal(order,"completed_qty").compareTo(decimal(order,"plan_qty"))>=0)
            throw conflict("仅已下达且未达到计划产量的工单可以短交关闭");
        if(orders.hasDocuments(actor,id,true)) throw conflict("请先处理未过账的领退料和完工单据");
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM mfg_cost_movement
                    WHERE tenant_id=? AND company_id=? AND work_order_id=? AND business_date>?)
                """,Boolean.class,actor.tenantId(),actor.companyId(),id,day)))
            throw invalid("短交日期不能早于工单最近一次生产过账或冲销的日期");
        if("allocate".equals(disposition) && !hasFinishedInPeriod(actor,id,period))
            throw invalid("短交期间没有完工入库，请将剩余在制成本计入生产成本差异");
    }

    private boolean hasFinishedInPeriod(CompanyAccess actor,long id,long period) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_delta),0) FROM mfg_cost_movement
                WHERE tenant_id=? AND company_id=? AND work_order_id=? AND period_id=? AND movement_type='mfg_receipt_in'
                """,BigDecimal.class,actor.tenantId(),actor.companyId(),id,period).signum()>0;
    }

    private List<ShortCloseMaterial> materials(CompanyAccess actor,long id,List<ShortCloseMaterial> input) {
        var rows=orders.lines(actor,id,true);
        if(input==null || input.size()>rows.size()) throw invalid("材料处理明细无效");
        var plan=new LinkedHashMap<Long,ShortCloseMaterial>();
        for(var line:input) {
            if(line==null) throw invalid("材料处理明细无效");
            long material=materialId(line.materialId());
            if(plan.putIfAbsent(material,line)!=null) throw invalid("同一物料行不可重复登记");
        }
        var normalized=new java.util.ArrayList<ShortCloseMaterial>();
        for(var row:rows) {
            long material=number(row,"id");
            var net=decimal(row,"issued_qty").subtract(decimal(row,"returned_qty"));
            var line=plan.remove(material);
            if(line==null && net.signum()==0) continue;
            if(line==null) throw invalid("尚有净领料未登记耗用或报废，请先处理剩余物料");
            var consumed=quantity(line.consumedQty());
            var scrapped=quantity(line.scrappedQty());
            if(consumed.add(scrapped).compareTo(net)!=0)
                throw invalid("已耗用与报废数量之和必须等于领料减已过账退料");
            var reason=text(line.scrapReason(),500,"材料报废说明",scrapped.signum()>0);
            normalized.add(new ShortCloseMaterial(Long.toString(material),consumed.toPlainString(),scrapped.toPlainString(),reason));
        }
        if(!plan.isEmpty()) throw invalid("材料处理明细须属于当前工单");
        return List.copyOf(normalized);
    }

    private static long materialId(String value) {
        try {return id(Long.valueOf(value),"工单物料");}
        catch(NumberFormatException error) {throw invalid("工单物料编号无效");}
    }

    private static BigDecimal quantity(String value) {
        if(value==null || !value.matches("[0-9]{1,18}(?:\\.[0-9]{1,6})?")) throw invalid("材料处理数量须为非负数，最多六位小数");
        return new BigDecimal(value).setScale(SCALE);
    }

    private static void authorize(CompanyAccess actor,String action) {
        if(!actor.hasPermission(RESOURCE+":view") || !actor.hasPermission(RESOURCE+":"+action))
            throw new ApiException(403,"FORBIDDEN","没有短交关闭操作权限");
    }
}
