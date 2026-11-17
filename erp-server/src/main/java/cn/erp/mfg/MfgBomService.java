package cn.erp.mfg;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.catalog.CatalogAvailability.SkuUnit;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.WorkflowBusiness;
import cn.erp.shared.WorkflowContext;
import cn.erp.shared.WorkflowPort;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.mfg.MfgPlanning.*;
import static cn.erp.mfg.MfgPlanningModels.*;

@Service
@Transactional
class MfgBomService implements WorkflowBusiness {
    private static final String RESOURCE="mfg_bom";
    private WorkflowPort workflow;

    @org.springframework.beans.factory.annotation.Autowired
    void workflow(WorkflowPort workflow) { this.workflow = workflow; }
    private static final int MAX_ALTERNATIVES=20;
    private final JdbcTemplate jdbc;
    private final CatalogAvailability catalog;
    private final MfgBoms boms;
    private final BomExpansion expansion;
    private final BusinessCommands commands;
    private final AuditService audit;

    MfgBomService(JdbcTemplate jdbc,CatalogAvailability catalog,MfgBoms boms,BomExpansion expansion,
                  BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc; this.catalog=catalog; this.boms=boms; this.expansion=expansion; this.commands=commands; this.audit=audit;
    }

    @Transactional(readOnly=true)
    Map<String,Object> list(CompanyAccess actor,Long sku,String status,String keyword,Long cursor,int limit) {
        return boms.list(actor,sku,status,keyword,cursor,limit);
    }

    @Transactional(readOnly=true)
    Map<String,Object> detail(CompanyAccess actor,long id) {return boms.detail(actor,id);}

    @Transactional(readOnly=true)
    Map<String,Object> explode(CompanyAccess actor,long id,String quantity,LocalDate day,Long warehouse) {
        var bom=boms.header(actor,id,false);
        var date=day==null ? today() : day(day,"展开日期");
        if(!MfgBoms.activeOn(bom,date)) throw invalid("展开日期不在物料清单生效区间内");
        if(warehouse!=null && !actor.allowsWarehouse(warehouse)) throw missing();
        if(warehouse!=null && jdbc.queryForList("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",
                Long.class,actor.tenantId(),actor.companyId(),id(warehouse,"展开仓库")).isEmpty()) throw missing();
        var plan=fit(qty(quantity,"展开数量").multiply(decimal(bom,"base_factor")),RoundingMode.CEILING);
        return Map.of("lines",expansion.view(actor,expansion.expand(actor,bom,plan,date,warehouse,true)));
    }

    Map<String,Object> create(CompanyAccess actor,String key,BomDraft input) {
        String identifier=commands.execute(actor,RESOURCE+".create",key,input,201,()->{
            boms.lockGraph(actor);
            long id=Ids.next(); var data=validate(actor,input,id);
            jdbc.update("""
                    INSERT INTO mfg_bom(id,tenant_id,company_id,sku_id,version_no,unit_id,base_qty,base_factor,valid_from,
                        valid_to,is_default,remark,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,actor.tenantId(),actor.companyId(),data.sku(),data.version(),data.unit(),data.base(),data.factor(),
                    data.from(),data.to(),data.isDefault(),data.remark(),actor.userId(),OrgOwnership.department(actor));
            writeLines(actor,id,data.lines());
            expansion.validateGraph(actor,id);
            audit.record(actor,RESOURCE+".create",RESOURCE,id,null,boms.detail(actor,id));
            return Long.toString(id);
        },String.class);
        return boms.detail(actor,Long.parseLong(identifier));
    }

    Map<String,Object> update(CompanyAccess actor,long id,BomDraft input) {
        boms.lockGraph(actor);
        var previous=boms.header(actor,id,true);
        checkRevision(previous,input.version());
        requireState(previous,"draft");
        var data=validate(actor,input,id);
        jdbc.update("""
                UPDATE mfg_bom SET sku_id=?,version_no=?,unit_id=?,base_qty=?,base_factor=?,valid_from=?,valid_to=?,
                    is_default=?,remark=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """,data.sku(),data.version(),data.unit(),data.base(),data.factor(),data.from(),data.to(),data.isDefault(),
                data.remark(),actor.tenantId(),actor.companyId(),id);
        jdbc.update("""
                DELETE x FROM mfg_bom_substitute x JOIN mfg_bom_line l ON l.tenant_id=x.tenant_id
                    AND l.company_id=x.company_id AND l.id=x.bom_line_id WHERE x.tenant_id=? AND x.company_id=? AND l.bom_id=?
                """,actor.tenantId(),actor.companyId(),id);
        jdbc.update("DELETE FROM mfg_bom_line WHERE tenant_id=? AND company_id=? AND bom_id=?",actor.tenantId(),actor.companyId(),id);
        writeLines(actor,id,data.lines());
        expansion.validateGraph(actor,id);
        var result=boms.detail(actor,id);
        audit.record(actor,RESOURCE+".update",RESOURCE,id,previous,result);
        return result;
    }

    Map<String,Object> command(CompanyAccess actor,long id,String action,String key,Revision input) {
        boms.header(actor,id,false);
        String identifier=commands.execute(actor,RESOURCE+"."+action,key,List.of(id,input),200,()->{
            workflow.guard(actor, RESOURCE, id, action.equals("disable") ? "cancel" : action);
            boms.lockGraph(actor);
            var previous=boms.header(actor,id,true);
            checkRevision(previous,input.version());
            switch(action) {
                case "submit" -> {
                    requireState(previous,"draft");
                    verifyReferences(actor,id,previous);
                    transition(actor,id,"pending");
                    workflow.start(actor, RESOURCE, id, Map.of());
                }
                case "approve" -> approve(actor,id,previous);
                case "disable" -> {requireState(previous,"draft","pending","approved"); transition(actor,id,"disabled");}
                default -> throw invalid("物料清单操作无效");
            }
            audit.record(actor,RESOURCE+"."+action,RESOURCE,id,previous,boms.detail(actor,id));
            return Long.toString(id);
        },String.class);
        return boms.detail(actor,Long.parseLong(identifier));
    }

    @Override
    public String businessType() { return RESOURCE; }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(ActorScope scope, long id) {
        var row = boms.header((CompanyAccess) scope, id, false);
        String reference = row.get("id") + "/" + row.get("version_no");
        return Map.of("documentNo", reference, "title", "物料清单 " + reference,
                "createdBy", row.get("owner_user_id"), "totalAmount", "0");
    }

    @Override
    public void decide(ActorScope scope, long id, String outcome, String comment, String key) {
        if (!WorkflowContext.allows(scope, RESOURCE, id)) throw new ApiException(403, "FORBIDDEN", "业务回调缺少流程上下文");
        var actor = (CompanyAccess) scope;
        boms.lockGraph(actor);
        var previous = boms.header(actor, id, true);
        if (outcome.equals("approved")) approve(actor, id, previous);
        else {
            requireState(previous, "pending");
            transition(actor, id, "draft");
        }
        audit.record(actor, RESOURCE + "." + outcome, RESOURCE, id, previous, boms.detail(actor, id));
    }

    private void approve(CompanyAccess actor,long id,Map<String,Object> previous) {
        requireState(previous,"pending");
        verifyReferences(actor,id,previous);
        if(Boolean.TRUE.equals(previous.get("is_default")) && boms.overlapsDefault(actor,id,number(previous,"sku_id"),
                date(previous,"valid_from"),end(date(previous,"valid_to")))) throw conflict("默认物料清单的生效日期与已审核版本重叠");
        expansion.validateGraph(actor,id);
        jdbc.update("""
                UPDATE mfg_bom SET status='approved',approver_id=?,approved_at=UTC_TIMESTAMP(3),version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,actor.userId(),actor.tenantId(),actor.companyId(),id);
    }

    private void verifyReferences(CompanyAccess actor,long id,Map<String,Object> bom) {
        var keys=new ArrayList<SkuUnit>();
        keys.add(new SkuUnit(number(bom,"sku_id"),number(bom,"unit_id")));
        for(var item:boms.lines(actor,id,true)) {
            keys.add(new SkuUnit(number(item,"component_sku_id"),number(item,"unit_id")));
            if(item.get("issue_warehouse_id")!=null) activeWarehouse(jdbc,actor,number(item,"issue_warehouse_id"));
            for(var replacement:boms.substitutes(actor,number(item,"id"),true))
                keys.add(new SkuUnit(number(replacement,"substitute_sku_id"),number(replacement,"unit_id")));
        }
        catalog.requireActiveSkus(actor,keys);
    }

    private void transition(CompanyAccess actor,long id,String status) {
        jdbc.update("UPDATE mfg_bom SET status=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                status,actor.tenantId(),actor.companyId(),id);
    }

    static void checkRevision(Map<String,Object> row,Long revision) {
        if(revision==null || revision<0 || number(row,"version")!=revision)
            throw new cn.erp.shared.ApiException(409,"VERSION_CONFLICT","单据已变更，请刷新后重试");
    }

    private void requireState(Map<String,Object> row,String... statuses) {
        if(!List.of(statuses).contains(row.get("status"))) throw conflict("当前物料清单状态不能执行此操作");
    }

    private record CheckedBom(long sku,long unit,String version,BigDecimal base,BigDecimal factor,LocalDate from,
                              LocalDate to,boolean isDefault,String remark,List<CheckedLine> lines) {}
    private record CheckedLine(long sku,long unit,BigDecimal qty,BigDecimal factor,BigDecimal scrap,Long warehouse,
                               boolean phantom,String remark,List<CheckedAlternative> alternatives) {}
    private record CheckedAlternative(long sku,long unit,BigDecimal ratio,BigDecimal baseRatio,int priority) {}

    private CheckedBom validate(CompanyAccess actor,BomDraft input,long id) {
        long sku=id(input.skuId(),"父项商品"), unit=id(input.unitId(),"父项单位");
        var from=day(input.validFrom(),"生效日期");
        var to=input.validTo()==null ? null : day(input.validTo(),"失效日期");
        if(to!=null && to.isBefore(from)) throw invalid("失效日期不能早于生效日期");
        var version=text(input.versionNo(),40,"版本号",true);
        if(!jdbc.queryForList("SELECT id FROM mfg_bom WHERE tenant_id=? AND company_id=? AND sku_id=? AND version_no=? AND id<>? FOR SHARE",
                Long.class,actor.tenantId(),actor.companyId(),sku,version,id).isEmpty()) throw conflict("该商品的物料清单版本号已存在");
        if(input.lines()==null || input.lines().isEmpty() || input.lines().size()>MAX_LINES) throw invalid("物料清单须包含 1 到 500 行子件");
        var references=new ArrayList<SkuUnit>(); references.add(new SkuUnit(sku,unit));
        for(var line:input.lines()) collectReferences(sku,line,references);
        var data=catalog.requireActiveSkus(actor,references);
        var lines=input.lines().stream().map(line->checkLine(actor,line,data)).toList();
        return new CheckedBom(sku,unit,version,qty(input.baseQty(),"基准用量"),factor(data,sku,unit),from,to,
                Boolean.TRUE.equals(input.isDefault()),text(input.remark(),500,"备注",false),lines);
    }

    private void collectReferences(long parent,BomLine input,List<SkuUnit> keys) {
        if(input==null) throw invalid("物料明细不能为空");
        long component=id(input.componentSkuId(),"子件"), unit=id(input.unitId(),"子件单位");
        if(parent==component) throw invalid("父项商品不能作为自己的子件");
        keys.add(new SkuUnit(component,unit));
        if(input.substitutes()==null) return;
        if(input.substitutes().size()>MAX_ALTERNATIVES || Boolean.TRUE.equals(input.phantom())&&!input.substitutes().isEmpty())
            throw invalid("每行最多登记 20 个替代料，虚拟组件展开后按实际子件登记替代料");
        var seen=new HashSet<Long>();
        for(var substitute:input.substitutes()) {
            if(substitute==null) throw invalid("替代料不能为空");
            long sku=id(substitute.substituteSkuId(),"替代料"), u=id(substitute.unitId(),"替代料单位");
            if(sku==component || sku==parent || !seen.add(sku)) throw invalid("替代料不能与父项或原料相同，也不能重复");
            keys.add(new SkuUnit(sku,u));
        }
    }

    private CheckedLine checkLine(CompanyAccess actor,BomLine line,Map<SkuUnit,Map<String,Object>> data) {
        long sku=line.componentSkuId(), unit=line.unitId();
        var factor=factor(data,sku,unit);
        Long warehouse=line.issueWarehouseId()==null ? null : activeWarehouse(jdbc,actor,line.issueWarehouseId());
        var alternatives=line.substitutes()==null ? List.<CheckedAlternative>of() : line.substitutes().stream().map(item->{
            long replacement=item.substituteSkuId(), replacementUnit=item.unitId();
            int priority=item.priority()==null ? 1 : item.priority();
            if(priority<1 || priority>65_535) throw invalid("替代顺序必须为 1 至 65535");
            var ratio=qty(item.ratio(),"替代比例");
            var base=fit(ratio.multiply(factor(data,replacement,replacementUnit)).divide(factor,SCALE,RoundingMode.HALF_UP),RoundingMode.UNNECESSARY);
            return new CheckedAlternative(replacement,replacementUnit,ratio,base,priority);
        }).toList();
        return new CheckedLine(sku,unit,qty(line.qty(),"子件用量"),factor,percentage(line.scrapRate(),"损耗率"),warehouse,
                Boolean.TRUE.equals(line.phantom()),text(line.remark(),500,"明细备注",false),alternatives);
    }

    private BigDecimal factor(Map<SkuUnit,Map<String,Object>> data,long sku,long unit) {
        return decimal(data.get(new SkuUnit(sku,unit)),"rate_to_base");
    }

    private void writeLines(CompanyAccess actor,long bom,List<CheckedLine> lines) {
        var details=new ArrayList<Object[]>(); var alternatives=new ArrayList<Object[]>();
        for(var line:lines) {
            long id=Ids.next();
            details.add(new Object[]{id,actor.tenantId(),actor.companyId(),bom,details.size()+1,line.sku(),line.unit(),line.qty(),
                    line.factor(),line.scrap(),line.warehouse(),line.phantom(),line.remark()});
            for(var item:line.alternatives()) alternatives.add(new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,
                    item.sku(),item.unit(),item.ratio(),item.baseRatio(),item.priority()});
        }
        jdbc.batchUpdate("""
                INSERT INTO mfg_bom_line(id,tenant_id,company_id,bom_id,line_no,component_sku_id,unit_id,qty,base_factor,
                    scrap_rate,issue_warehouse_id,is_phantom,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,details);
        if(!alternatives.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO mfg_bom_substitute(id,tenant_id,company_id,bom_line_id,substitute_sku_id,unit_id,ratio,base_ratio,priority)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,alternatives);
    }
}
