package cn.erp.mfg;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.catalog.CatalogAvailability.SkuUnit;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.DocumentNumbers;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.mfg.MfgPlanning.*;
import static cn.erp.mfg.MfgPlanningModels.*;

@Service
@Transactional
class MfgWorkOrderService {
    private static final String RESOURCE="mfg_work_order";
    private final JdbcTemplate jdbc;
    private final MfgOrders orders;
    private final MfgBoms boms;
    private final BomExpansion expansion;
    private final CatalogAvailability catalog;
    private final InventoryService inventory;
    private final DocumentNumbers numbers;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ObjectMapper json;

    MfgWorkOrderService(JdbcTemplate jdbc,MfgOrders orders,MfgBoms boms,BomExpansion expansion,CatalogAvailability catalog,
                        InventoryService inventory,DocumentNumbers numbers,BusinessCommands commands,AuditService audit,ObjectMapper json) {
        this.jdbc=jdbc; this.orders=orders; this.boms=boms; this.expansion=expansion; this.catalog=catalog;
        this.inventory=inventory; this.numbers=numbers; this.commands=commands; this.audit=audit; this.json=json;
    }

    @Transactional(readOnly=true)
    Map<String,Object> list(CompanyAccess actor,Long warehouse,String status,String keyword,Long cursor,int limit) {
        return orders.list(actor,warehouse,status,keyword,cursor,limit);
    }

    @Transactional(readOnly=true)
    Map<String,Object> detail(CompanyAccess actor,long id) {return orders.detail(actor,id);}

    Map<String,Object> create(CompanyAccess actor,String key,WorkOrderDraft input) {
        String id=commands.execute(actor,RESOURCE+".create",key,input,201,()->{
            boms.lockGraph(actor);
            return insert(actor,checkDraft(actor,input),null,null);
        },String.class);
        return orders.detail(actor,Long.parseLong(id));
    }

    Map<String,Object> fromSalesOrder(CompanyAccess actor,String key,FromSalesOrder input) {
        if(!actor.hasPermission("sales_order:view")) throw new ApiException(403,"FORBIDDEN","没有查看销售订单的权限");
        long sales=id(input.salesOrderId(),"销售订单"), line=id(input.salesOrderLineId(),"销售订单行");
        salesLine(actor,sales,line,false);
        String id=commands.execute(actor,RESOURCE+".from_sales_order",key,input,201,()->{
            boms.lockGraph(actor);
            var source=salesLine(actor,sales,line,true);
            long sku=number(source,"sku_id"), warehouse=number(source,"warehouse_id");
            if(id(input.receiveWarehouseId(),"完工仓")!=warehouse) throw invalid("销售订单生成工单的完工仓必须与原发货仓一致");
            var remaining=shortage(actor,source);
            if(remaining.signum()<=0) throw conflict("该销售明细暂无需要生产的缺货数量");
            var unit=BomExpansion.baseUnit(jdbc,actor,sku,true);
            return insert(actor,checkDraft(actor,new WorkOrderDraft(sku,input.bomId(),unit,remaining.toPlainString(),
                    input.issueWarehouseId(),warehouse,input.planStartDate(),input.planFinishDate(),input.overReceivePct(),null,input.remark())),sales,line);
        },String.class);
        return orders.detail(actor,Long.parseLong(id));
    }

    private Map<String,Object> salesLine(CompanyAccess actor,long order,long line,boolean lock) {
        var header=jdbc.queryForList("SELECT s.status FROM sales_order s WHERE s.tenant_id=? AND s.company_id=? AND s.id=?"
                +" AND s.is_deleted=0"+OrgOwnership.filter(actor,"s")+(lock?" FOR UPDATE":""),String.class,actor.tenantId(),actor.companyId(),order);
        if(header.isEmpty()) throw missing();
        if(lock && !"approved".equals(header.getFirst())) throw conflict("仅已审批的销售订单可生成生产工单");
        var rows=jdbc.queryForList("""
                SELECT id,sku_id,warehouse_id,base_qty,closed_base_qty,shipped_base_qty,pending_ship_base_qty
                FROM sales_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0 ORDER BY id
                """+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),order);
        if(rows.stream().anyMatch(row->row.get("warehouse_id")==null || !actor.allowsWarehouse(number(row,"warehouse_id")))) throw missing();
        return rows.stream().filter(row->number(row,"id")==line).findFirst().orElseThrow(MfgPlanning::missing);
    }

    private BigDecimal shortage(CompanyAccess actor,Map<String,Object> source) {
        long line=number(source,"id"),sku=number(source,"sku_id"),warehouse=number(source,"warehouse_id");
        var available=inventory.balance(actor,new Key(warehouse,sku)).available().max(BigDecimal.ZERO);
        var reserved=jdbc.queryForList("""
                SELECT remaining_qty FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND order_line_id=?
                    AND warehouse_id=? AND sku_id=? AND status='active' FOR SHARE
                """,BigDecimal.class,actor.tenantId(),actor.companyId(),line,warehouse,sku).stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        var jobs=jdbc.queryForList("""
                SELECT GREATEST(plan_qty-completed_qty,0) FROM mfg_work_order WHERE tenant_id=? AND company_id=?
                    AND source_line_id=? AND sku_id=? AND receive_warehouse_id=? AND status IN ('draft','released','in_progress')
                FOR SHARE
                """,BigDecimal.class,actor.tenantId(),actor.companyId(),line,sku,warehouse).stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        var demand=decimal(source,"base_qty").subtract(decimal(source,"closed_base_qty")).subtract(decimal(source,"shipped_base_qty"));
        return demand.subtract(decimal(source,"pending_ship_base_qty").max(reserved)).subtract(available).subtract(jobs);
    }

    Map<String,Object> update(CompanyAccess actor,long id,WorkOrderDraft input) {
        boms.lockGraph(actor);
        var old=orders.header(actor,id,true);
        MfgBomService.checkRevision(old,input.version());
        current(old,"draft");
        var draft=checkDraft(actor,input);
        if("sales_order".equals(old.get("source_type")) && (draft.sku()!=number(old,"sku_id") || draft.receive()!=number(old,"receive_warehouse_id")
                || draft.quantity().compareTo(decimal(old,"plan_qty"))!=0)) throw invalid("销售订单生成的工单不能更改商品、计划数量或完工仓");
        jdbc.update("""
                UPDATE mfg_work_order SET sku_id=?,bom_id=?,unit_id=?,plan_qty=?,issue_warehouse_id=?,receive_warehouse_id=?,
                    plan_start_date=?,plan_finish_date=?,over_receive_pct=?,remark=?,updated_by=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,draft.sku(),draft.bom(),draft.unit(),draft.quantity(),draft.issue(),draft.receive(),draft.start(),draft.finish(),draft.over(),
                draft.remark(),actor.userId(),actor.tenantId(),actor.companyId(),id);
        var latest=orders.detail(actor,id);
        audit.record(actor,RESOURCE+".update",RESOURCE,id,old,latest);
        return latest;
    }

    Map<String,Object> command(CompanyAccess actor,long id,String action,String key,Revision input) {
        orders.header(actor,id,false);
        String changed=commands.execute(actor,RESOURCE+"."+action,key,List.of(id,input),200,()->{
            if(action.equals("release")) boms.lockGraph(actor);
            var previous=orders.header(actor,id,true);
            MfgBomService.checkRevision(previous,input.version());
            switch(action) {
                case "release" -> release(actor,id,previous);
                case "close" -> close(actor,id,previous);
                case "cancel" -> cancel(actor,id,previous);
                default -> throw invalid("生产工单操作无效");
            }
            audit.record(actor,RESOURCE+"."+action,RESOURCE,id,previous,orders.detail(actor,id));
            return Long.toString(id);
        },String.class);
        return orders.detail(actor,Long.parseLong(changed));
    }

    private record CheckedDraft(long sku,long bom,long unit,BigDecimal quantity,long issue,long receive,LocalDate start,LocalDate finish,
                                BigDecimal over,String remark) {}

    private CheckedDraft checkDraft(CompanyAccess actor,WorkOrderDraft draft) {
        long sku=id(draft.skuId(),"产成品"), unit=id(draft.unitId(),"计划单位");
        LocalDate start=day(draft.planStartDate(),"计划开工日"), finish=day(draft.planFinishDate(),"计划完成日");
        if(finish.isBefore(start)) throw invalid("计划完成日不能早于开工日");
        var bom=draft.bomId()==null ? boms.effectiveDefault(actor,sku,start,true) : boms.effective(actor,id(draft.bomId(),"物料清单"),sku,start,true);
        if(bom==null) throw invalid("产成品未维护计划开工日有效的默认物料清单");
        long issue=activeWarehouse(jdbc,actor,draft.issueWarehouseId()),receive=activeWarehouse(jdbc,actor,draft.receiveWarehouseId());
        long baseUnit=BomExpansion.baseUnit(jdbc,actor,sku,true);
        var selected=new SkuUnit(sku,unit);
        var data=catalog.requireActiveSkus(actor,List.of(selected,new SkuUnit(sku,baseUnit)));
        var plan=fit(qty(draft.planQty(),"计划生产数量").multiply(decimal(data.get(selected),"rate_to_base")),RoundingMode.CEILING);
        return new CheckedDraft(sku,number(bom,"id"),baseUnit,plan,issue,receive,start,finish,
                percentage(draft.overReceivePct(),"允许超额完工比例"),text(draft.remark(),500,"备注",false));
    }

    private String insert(CompanyAccess actor,CheckedDraft draft,Long sales,Long line) {
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO mfg_work_order(id,tenant_id,company_id,document_no,sku_id,bom_id,unit_id,plan_qty,issue_warehouse_id,
                    receive_warehouse_id,plan_start_date,plan_finish_date,source_type,source_id,source_line_id,over_receive_pct,
                    remark,owner_department_id,owner_user_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),numbers.next(actor,"WO",draft.start()),draft.sku(),draft.bom(),draft.unit(),
                draft.quantity(),draft.issue(),draft.receive(),draft.start(),draft.finish(),sales==null?"manual":"sales_order",sales,line,
                draft.over(),draft.remark(),OrgOwnership.department(actor),actor.userId(),actor.userId(),actor.userId());
        audit.record(actor,RESOURCE+".create",RESOURCE,id,null,orders.detail(actor,id));
        return Long.toString(id);
    }

    private void release(CompanyAccess actor,long id,Map<String,Object> order) {
        current(order,"draft");
        var start=date(order,"plan_start_date");
        long sku=number(order,"sku_id"), issue=activeWarehouse(jdbc,actor,number(order,"issue_warehouse_id"));
        activeWarehouse(jdbc,actor,number(order,"receive_warehouse_id"));
        var bom=boms.effective(actor,number(order,"bom_id"),sku,start,true);
        var planned=expansion.expand(actor,bom,decimal(order,"plan_qty"),start,issue,false);
        if(planned.isEmpty()) throw invalid("该物料清单没有可领料明细");
        var warehouses=planned.stream().map(BomExpansion.Material::warehouseId).distinct().sorted().toList();
        for(var warehouse:warehouses) activeWarehouse(jdbc,actor,warehouse);
        var references=new ArrayList<>(planned.stream().flatMap(item->Stream.concat(Stream.of(new SkuUnit(item.skuId(),item.unitId())),
                item.alternatives().stream().map(alternate->new SkuUnit(alternate.skuId(),alternate.unitId())))).toList());
        references.add(new SkuUnit(sku,number(order,"unit_id")));
        catalog.requireActiveSkus(actor,references);
        freeze(actor,id,planned);
        jdbc.update("""
                UPDATE mfg_work_order SET status='released',released_at=UTC_TIMESTAMP(3),version=version+1,updated_by=?
                WHERE tenant_id=? AND company_id=? AND id=?
                """,actor.userId(),actor.tenantId(),actor.companyId(),id);
    }

    private void freeze(CompanyAccess actor,long order,List<BomExpansion.Material> planned) {
        var materials=new ArrayList<Object[]>(); var substitutes=new ArrayList<Object[]>();
        for(var row:planned) {
            long id=Ids.next();
            materials.add(new Object[]{id,actor.tenantId(),actor.companyId(),order,materials.size()+1,row.bomLineId(),row.skuId(),row.unitId(),
                    row.warehouseId(),row.quantity(),json.writeValueAsString(row.path())});
            for(var alt:row.alternatives()) substitutes.add(new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,alt.skuId(),alt.unitId(),alt.ratio(),alt.priority()});
        }
        jdbc.batchUpdate("""
                INSERT INTO mfg_work_order_material(id,tenant_id,company_id,work_order_id,line_no,bom_line_id,component_sku_id,unit_id,
                    issue_warehouse_id,required_qty,path_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,materials);
        if(!substitutes.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO mfg_work_order_substitute(id,tenant_id,company_id,work_order_material_id,substitute_sku_id,unit_id,ratio,priority)
                VALUES(?,?,?,?,?,?,?,?)
                """,substitutes);
    }

    private void close(CompanyAccess actor,long id,Map<String,Object> order) {
        current(order,"completed");
        if(decimal(order,"completed_qty").compareTo(decimal(order,"plan_qty"))<0) throw conflict("工单尚未完成计划数量");
        if(hasDocuments(actor,id,true)) throw conflict("请先处理未过账的领退料和完工单据");
        jdbc.update("""
                UPDATE mfg_work_order SET status='closed',closed_at=UTC_TIMESTAMP(3),updated_by=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,actor.userId(),actor.tenantId(),actor.companyId(),id);
    }

    private void cancel(CompanyAccess actor,long id,Map<String,Object> order) {
        current(order,"draft","released");
        if(hasDocuments(actor,id,false)) throw conflict("工单已有生产单据，须先处理后才能取消");
        jdbc.update("UPDATE mfg_work_order SET status='cancelled',updated_by=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(),actor.tenantId(),actor.companyId(),id);
    }

    private boolean hasDocuments(CompanyAccess actor,long id,boolean pendingOnly) {
        String states=pendingOnly ? "status IN ('draft','pending','approved')" : "status NOT IN ('reversed','cancelled','voided')";
        for(String table:List.of("mfg_issue_doc","mfg_receipt_doc")) {
            if(!jdbc.queryForList("SELECT id FROM "+table+" WHERE tenant_id=? AND company_id=? AND work_order_id=? AND "+states+" LIMIT 1 FOR SHARE",
                    Long.class,actor.tenantId(),actor.companyId(),id).isEmpty()) return true;
        }
        return false;
    }

    private void current(Map<String,Object> order,String... states) {
        if(!List.of(states).contains(order.get("status"))) throw conflict("当前生产工单状态不能执行此操作");
    }
}
