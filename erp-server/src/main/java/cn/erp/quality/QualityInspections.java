package cn.erp.quality;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.Posting;
import cn.erp.inventory.InventoryModels.Source;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.ReceiptQualityPort;
import cn.erp.sysadmin.DictionaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.quality.QualityModels.*;

@Service
@Transactional
class QualityInspections implements ReceiptQualityPort {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final QualitySettings settings;
    private final InventoryService inventory;
    private final QualityDispositions dispositions;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final DictionaryService dictionaries;

    QualityInspections(JdbcTemplate jdbc,ObjectMapper json,QualitySettings settings,InventoryService inventory,
                       QualityDispositions dispositions,BusinessCommands commands,AuditService audit,DictionaryService dictionaries){
        this.jdbc=jdbc;this.json=json;this.settings=settings;this.inventory=inventory;this.dispositions=dispositions;
        this.commands=commands;this.audit=audit;this.dictionaries=dictionaries;
    }

    @Override
    public boolean requires(ActorScope actor,long skuId,long supplierId){return settings.matching(company(actor),skuId,supplierId)!=null;}

    @Override
    public void received(ActorScope scope,long receiptId,long lineId,long skuId,long supplierId,long warehouseId,
                         String lotKey,String locationKey,BigDecimal qty,LocalDate date){
        CompanyAccess actor=company(scope);
        var template=settings.matching(actor,skuId,supplierId);
        if(template==null)throw conflict("收货时来料检规则已变化，请重试");
        long id=Ids.next();
        int count=jdbc.update("""
                INSERT INTO qc_inspection(id,tenant_id,company_id,inspection_no,source_id,source_line_id,sku_id,supplier_id,warehouse_id,
                  lot_key,location_key,received_qty,round_qty,sample_qty,snapshot_json,business_date,owner_user_id,owner_department_id)
                SELECT ?,h.tenant_id,h.company_id,?,h.id,?,?,?,?,?,?,?,?,?,?,?,h.owner_user_id,h.owner_department_id
                FROM goods_receipt h WHERE h.tenant_id=? AND h.company_id=? AND h.id=? AND h.warehouse_id=?
                """,id,"IQC-"+id,lineId,skuId,supplierId,warehouseId,lotKey,locationKey,qty,qty,sample(template,qty),
                json.writeValueAsString(template),date,actor.tenantId(),actor.companyId(),receiptId,warehouseId);
        if(count!=1)throw missing();
        audit.record(actor,"qc_inspection.create","qc_inspection",id,null,Map.of("sourceId",Long.toString(receiptId),"sourceLineId",Long.toString(lineId)));
    }

    @Transactional(readOnly=true)
    Map<String,Object> list(CompanyAccess actor,String status,Long warehouseId,Long before,int limit,boolean nonconforming){
        if(limit<1||limit>200||before!=null&&before<=0)throw invalid("分页参数无效");
        if(status!=null)option(status,Set.of("draft","submitted","approved","cancelled"),"检验状态");
        if(warehouseId!=null&&!actor.allowsWarehouse(warehouseId))throw missing();
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),before==null?Long.MAX_VALUE:before));
        String sql="""
                SELECT q.id,q.inspection_no,q.source_id,q.sku_id,q.supplier_id,q.warehouse_id,q.lot_key,q.location_key,q.round_qty,
                    q.sample_qty,q.qualified_qty,q.concession_qty,q.unqualified_qty,q.result,q.status,q.version,q.business_date,
                    s.name sku_name,p.name supplier_name,w.name warehouse_name
                FROM qc_inspection q JOIN sku s ON s.tenant_id=q.tenant_id AND s.id=q.sku_id
                JOIN business_partner p ON p.tenant_id=q.tenant_id AND p.id=q.supplier_id
                JOIN warehouse w ON w.tenant_id=q.tenant_id AND w.company_id=q.company_id AND w.id=q.warehouse_id
                WHERE q.tenant_id=? AND q.company_id=? AND q.id<?
                """+OrgOwnership.filter(actor,"q");
        if(status!=null){sql+=" AND q.status=?";args.add(status);}
        if(nonconforming)sql+=" AND q.status='approved' AND q.unqualified_qty>0";
        if(warehouseId!=null){sql+=" AND q.warehouse_id=?";args.add(warehouseId);}
        if(!actor.allWarehouses()){
            if(actor.warehouseIds().isEmpty())return page(List.of(),limit);
            sql+=" AND q.warehouse_id IN ("+String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"))+")";
            args.addAll(actor.warehouseIds());
        }
        args.add(limit+1);
        return page(jdbc.queryForList(sql+" ORDER BY q.id DESC LIMIT ?",args.toArray()),limit);
    }

    @Transactional(readOnly=true)
    Map<String,Object> detail(CompanyAccess actor,long id){
        var row=find(actor,id,false);
        var view=ApiRows.row(row);view.remove("snapshotJson");
        view.put("snapshot",snapshot(row));
        view.put("values",jdbc.queryForList("SELECT item_id,round_no,measured_value,judgement FROM qc_inspection_value WHERE tenant_id=? AND company_id=? AND inspection_id=? ORDER BY round_no,item_id",
                actor.tenantId(),actor.companyId(),id).stream().map(ApiRows::row).toList());
        view.put("defects",jdbc.queryForList("SELECT id,round_no,defect_code,qty,remark FROM qc_defect WHERE tenant_id=? AND company_id=? AND inspection_id=? ORDER BY id",
                actor.tenantId(),actor.companyId(),id).stream().map(ApiRows::row).toList());
        view.put("rounds",jdbc.queryForList("SELECT round_no,qualified_qty,unqualified_qty,concession_qty,concession_reason,inspector_id,approver_id,approved_at FROM qc_result_round WHERE tenant_id=? AND company_id=? AND inspection_id=? ORDER BY round_no",
                actor.tenantId(),actor.companyId(),id).stream().map(ApiRows::row).toList());
        view.put("dispositions",dispositions.list(actor,id));
        view.put("remainingQty",decimal(row,"unqualified_qty").subtract(dispositions.allocated(actor,row)).toPlainString());
        return view;
    }

    Map<String,Object> submit(CompanyAccess actor,long id,String key,Submission input){
        if(input==null)throw invalid("请填写检验结果");
        find(actor,id,false);
        return execute(actor,"submit",key,List.of(id,input),()->{
            var row=find(actor,id,true);version(input.version(),row);state(row,"draft");
            var quantities=quantities(actor,row,input);
            var snapshot=snapshot(row);
            saveOutcomes(actor,row,snapshot,input,quantities.unqualified().add(quantities.concession()));
            jdbc.update("""
                    UPDATE qc_inspection SET inspector_id=?,approver_id=NULL,approved_at=NULL,qualified_qty=?,unqualified_qty=?,
                      concession_qty=?,concession_reason=?,result=?,status='submitted',version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,actor.userId(),quantities.qualified(),quantities.unqualified(),quantities.concession(),quantities.reason(),
                    result(quantities),actor.tenantId(),actor.companyId(),id);
            return changed(actor,id,"submit",row);
        });
    }

    Map<String,Object> decide(CompanyAccess actor,long id,String action,String key,Decision input){
        if(input==null)throw invalid("请提供审核版本");
        find(actor,id,false);
        return execute(actor,action,key,List.of(id,input),()->{
            var row=find(actor,id,true);version(input.version(),row);state(row,"submitted");
            if(id(row,"inspector_id")==actor.userId())throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","质量审核人不能是检验员");
            if(action.equals("approve")) approve(actor,row);
            else reject(actor,row,text(input.reason(),200,"驳回原因",true));
            return changed(actor,id,action,row);
        });
    }

    Map<String,Object> dispose(CompanyAccess actor,long id,String key,Disposition input){
        if(input==null)throw invalid("请填写不合格品处置");
        find(actor,id,false);
        return execute(actor,"dispose",key,List.of(id,input),()->{
            var row=find(actor,id,true);version(input.version(),row);state(row,"approved");
            String action=option(input.action(),Set.of("return","scrap","reinspect"),"处置类型");
            String reason=text(input.reason(),200,"处置原因",true);
            BigDecimal remaining=decimal(row,"unqualified_qty").subtract(dispositions.allocated(actor,row));
            BigDecimal qty=number(input.qty(),"处置数量",false);
            if(qty.signum()<=0||qty.compareTo(remaining)>0)throw invalid("处置数量超过剩余不合格数量");
            if((action.equals("reinspect")||serialized(actor,row))&&qty.compareTo(remaining)!=0)
                throw invalid("复检或序列号处置须覆盖本单剩余数量");
            dispositions.create(actor,row,action,qty,reason);
            if(action.equals("reinspect"))jdbc.update("""
                    UPDATE qc_inspection SET round_no=round_no+1,round_qty=?,sample_qty=?,status='draft',result='pending',
                      qualified_qty=0,unqualified_qty=0,concession_qty=0,concession_reason=NULL,inspector_id=NULL,approver_id=NULL,
                      approved_at=NULL,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                    """,qty,sample(snapshot(row),qty),actor.tenantId(),actor.companyId(),id);
            else jdbc.update("UPDATE qc_inspection SET version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),id);
            return changed(actor,id,"dispose",row);
        });
    }

    @Override
    public Hold beforePurchaseReturn(ActorScope scope,long returnId,long returnLineId,long receiptLineId,BigDecimal qty){
        CompanyAccess actor=company(scope);
        var row=dispositions.linked(actor,"purchase_return",returnId);
        if(row.isEmpty())return null;
        if(id(row,"source_line_id")!=receiptLineId)throw conflict("退货明细与来料检处置不一致");
        return validateDisposition(actor,row,"purchase_return",returnLineId,qty);
    }

    @Override
    public Hold beforeScrap(ActorScope scope,long adjustmentId,long adjustmentLineId,BigDecimal qty){
        CompanyAccess actor=company(scope);
        var row=dispositions.linked(actor,"stock_adjustment",adjustmentId);
        if(row.isEmpty())return null;
        return validateDisposition(actor,row,"stock_adjustment",adjustmentLineId,qty);
    }

    private Hold validateDisposition(CompanyAccess actor,Map<String,Object> row,String table,long line,BigDecimal qty){
        if(qty.compareTo(decimal(row,"disposition_qty"))!=0)throw conflict("处置明细数量与检验单不一致");
        if(!actor.allowsWarehouse(id(row,"warehouse_id")))throw missing();
        OrgOwnership.require(actor,row);
        String parent=table.equals("purchase_return")?"return_id":"adjustment_id";
        var target=jdbc.queryForMap("SELECT sku_id,stock_selection,"+parent+" parent_id FROM "+table+"_line WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(),actor.companyId(),line);
        var coordinates=inventory.decodeSelection(target.get("stock_selection"));
        var expected=selection(row);
        var draft=jdbc.queryForMap("SELECT warehouse_id,(SELECT COUNT(*) FROM "+table+"_line l WHERE l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l."+parent+"=h.id) line_count FROM "+table+" h WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(),actor.companyId(),target.get("parent_id"));
        if(id(draft,"line_count")!=1||id(draft,"warehouse_id")!=id(row,"warehouse_id")||
                id(target,"sku_id")!=id(row,"sku_id")||coordinates==null||
                !Objects.equals(coordinates.batchId(),expected.batchId())||
                !Objects.equals(coordinates.locationId(),expected.locationId()))
            throw conflict("处置单的商品、批次或库位与检验单不一致");
        return new Hold(id(row,"source_id"),id(row,"source_line_id"));
    }

    @Override
    public void beforeReceiptReversal(ActorScope scope,long receiptId){
        CompanyAccess actor=company(scope);
        var rows=jdbc.queryForList("""
                SELECT id,status,round_no FROM qc_inspection
                WHERE tenant_id=? AND company_id=? AND source_id=? ORDER BY id FOR UPDATE
                """,actor.tenantId(),actor.companyId(),receiptId);
        for(var row:rows){
            if(!"draft".equals(row.get("status"))||id(row,"round_no")!=1||
                    !dispositions.list(actor,id(row,"id")).isEmpty())
                throw new ApiException(422,"DEPENDENCY_EXISTS","收货单已有检验或处置结果，不能直接冲销");
        }
        for(var row:rows){
            jdbc.update("UPDATE qc_inspection SET status='cancelled',version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                    actor.tenantId(),actor.companyId(),row.get("id"));
            audit.record(actor,"qc_inspection.cancel","qc_inspection",id(row,"id"),Map.of("status","draft"),Map.of("status","cancelled"));
        }
    }

    private void approve(CompanyAccess actor,Map<String,Object> row){
        BigDecimal qty=decimal(row,"qualified_qty").add(decimal(row,"concession_qty"));
        if(qty.signum()>0)release(actor,row,new Source("qc_inspection",id(row,"id"),Ids.next()),LocalDate.now(ZONE),qty);
        jdbc.update("""
                INSERT INTO qc_result_round(tenant_id,company_id,inspection_id,round_no,qualified_qty,unqualified_qty,concession_qty,
                  concession_reason,inspector_id,approver_id,approved_at) VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """,actor.tenantId(),actor.companyId(),row.get("id"),row.get("round_no"),row.get("qualified_qty"),row.get("unqualified_qty"),
                row.get("concession_qty"),row.get("concession_reason"),row.get("inspector_id"),actor.userId());
        jdbc.update("UPDATE qc_inspection SET status='approved',approver_id=?,approved_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(),actor.tenantId(),actor.companyId(),row.get("id"));
    }

    private void reject(CompanyAccess actor,Map<String,Object> row,String reason){
        jdbc.update("UPDATE qc_inspection SET status='draft',result='pending',approver_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(),actor.tenantId(),actor.companyId(),row.get("id"));
        audit.record(actor,"qc_inspection.reject_reason","qc_inspection",id(row,"id"),null,Map.of("reason",reason));
    }

    private void release(CompanyAccess actor,Map<String,Object> row,Source action,LocalDate date,BigDecimal qty){
        var periods=jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=? AND status='open' FOR SHARE",Long.class,
                actor.tenantId(),actor.companyId(),date,date);
        if(periods.size()!=1)throw new ApiException(422,"PERIOD_CLOSED","检验业务日期没有对应的开放期间");
        var posting=new Posting(new Key(id(row,"warehouse_id"),id(row,"sku_id")),action,date,periods.getFirst(),"unblock");
        var selection=selection(row);
        inventory.releaseHold(actor,posting,selection,"quality_hold",new Source("goods_receipt_line",id(row,"source_id"),id(row,"source_line_id")),qty);
        if(selection.batchId()!=null&&Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM inv_batch WHERE tenant_id=? AND company_id=? AND id=?
                  AND (status='expired' OR expiry_date<?))
                """,Boolean.class,actor.tenantId(),actor.companyId(),selection.batchId(),date))){
            var expired=new Posting(posting.key(),new Source("qc_inspection",action.id(),Ids.next()),date,posting.periodId(),"block");
            inventory.freeze(actor,expired,qty,true,selection,"expired");
        }
    }

    private Quantities quantities(CompanyAccess actor,Map<String,Object> row,Submission input){
        BigDecimal qualified=number(input.qualifiedQty(),"合格数量",false),unqualified=number(input.unqualifiedQty(),"不合格数量",false);
        BigDecimal concession=blank(input.concessionQty())?BigDecimal.ZERO:number(input.concessionQty(),"让步接收数量",false);
        if(qualified.add(unqualified).add(concession).compareTo(decimal(row,"round_qty"))!=0)throw invalid("三类判定数量合计必须等于本轮待检数量");
        if(serialized(actor,row)&&unqualified.signum()>0&&qualified.add(concession).signum()>0)
            throw invalid("含序列号的收货行需要整体判定，部分合格请按检验结果分单收货");
        return new Quantities(qualified,unqualified,concession,text(input.concessionReason(),500,"让步接收原因",concession.signum()>0));
    }

    private record Quantities(BigDecimal qualified,BigDecimal unqualified,BigDecimal concession,String reason){}
    private String result(Quantities value){
        return value.unqualified().signum()==0?"passed":value.qualified().add(value.concession()).signum()==0?"failed":"partial";
    }

    private void saveOutcomes(CompanyAccess actor,Map<String,Object> row,Snapshot template,Submission input,BigDecimal failures){
        Map<String,Value> submitted=new HashMap<>();
        List<Value> values=input.values()==null?List.of():input.values();
        if(values.size()>100)throw invalid("检验结果最多100项");
        for(var value:values)if(value==null||value.itemId()==null||submitted.putIfAbsent(value.itemId(),value)!=null)throw invalid("检验项目重复或无效");
        var outcome=new ArrayList<Object[]>();boolean anyNg=false;
        for(var criterion:template.checks()){
            var value=submitted.remove(criterion.itemId());
            if(value==null){if(criterion.required())throw invalid("请填写必检项目："+criterion.name());continue;}
            String measured=text(value.measuredValue(),160,"测量值",true);
            String judgement=judge(criterion,value,measured);anyNg|=judgement.equals("ng");
            outcome.add(new Object[]{actor.tenantId(),actor.companyId(),row.get("id"),row.get("round_no"),id(value.itemId()),measured,judgement});
        }
        if(!submitted.isEmpty())throw invalid("存在不属于本次模板的检验项目");
        if(anyNg&&failures.signum()==0)throw invalid("存在不通过的检验项目，请填写不合格或让步接收数量");
        jdbc.update("DELETE FROM qc_inspection_value WHERE tenant_id=? AND company_id=? AND inspection_id=? AND round_no=?",
                actor.tenantId(),actor.companyId(),row.get("id"),row.get("round_no"));
        jdbc.batchUpdate("INSERT INTO qc_inspection_value(tenant_id,company_id,inspection_id,round_no,item_id,measured_value,judgement) VALUES(?,?,?,?,?,?,?)",outcome);
        saveDefects(actor,row,input.defects(),failures);
    }

    private String judge(Criterion item,Value input,String measured){
        if(item.type().equals("qualitative"))return option(input.judgement(),Set.of("ok","ng"),"判定结果");
        BigDecimal value=number(measured,"测量值",true);
        boolean outside=item.lowerLimit()!=null&&value.compareTo(new BigDecimal(item.lowerLimit()))<0
                ||item.upperLimit()!=null&&value.compareTo(new BigDecimal(item.upperLimit()))>0;
        return outside?"ng":"ok";
    }

    private void saveDefects(CompanyAccess actor,Map<String,Object> row,List<Defect> defects,BigDecimal failures){
        jdbc.update("DELETE FROM qc_defect WHERE tenant_id=? AND company_id=? AND inspection_id=? AND round_no=?",
                actor.tenantId(),actor.companyId(),row.get("id"),row.get("round_no"));
        if(defects==null||defects.isEmpty())return;
        if(defects.size()>100)throw invalid("问题明细最多100项");
        var rows=new ArrayList<Object[]>();BigDecimal total=BigDecimal.ZERO;
        for(var item:defects){
            if(item==null)throw invalid("问题明细无效");
            BigDecimal qty=number(item.qty(),"缺陷数量",false);total=total.add(qty);
            if(qty.signum()<=0||total.compareTo(failures)>0)throw invalid("缺陷数量超出不通过数量");
            String defect=text(item.defectCode(),40,"缺陷类型",true);
            dictionaries.validateValue(actor.tenantId(),"quality_defect",defect,false);
            rows.add(new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),row.get("id"),row.get("round_no"),defect,qty,text(item.remark(),200,"说明",false)});
        }
        jdbc.batchUpdate("INSERT INTO qc_defect(id,tenant_id,company_id,inspection_id,round_no,defect_code,qty,remark) VALUES(?,?,?,?,?,?,?,?)",rows);
    }

    private Snapshot snapshot(Map<String,Object> row){return json.readValue(row.get("snapshot_json").toString(),Snapshot.class);}
    private BigDecimal sample(Snapshot snapshot,BigDecimal qty){
        if(snapshot.sampling().equals("full"))return qty;
        BigDecimal value=new BigDecimal(snapshot.samplingValue());
        return (snapshot.sampling().equals("percent")?qty.multiply(value).divide(BigDecimal.valueOf(100),0,RoundingMode.CEILING):value).min(qty);
    }
    private boolean serialized(CompanyAccess actor,Map<String,Object> row){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT serial_managed FROM sku WHERE tenant_id=? AND id=?",Boolean.class,actor.tenantId(),row.get("sku_id")));
    }
    static StockSelection selection(Map<String,Object> row){
        String lot=Objects.toString(row.get("lot_key"),""),location=Objects.toString(row.get("location_key"),"");
        return new StockSelection(lot.isEmpty()?null:Long.valueOf(lot),null,null,null,location.isEmpty()?null:Long.valueOf(location),List.of());
    }
    private CompanyAccess company(ActorScope actor){if(actor instanceof CompanyAccess scope)return scope;throw forbidden();}
    private Map<String,Object> find(CompanyAccess actor,long id,boolean lock){
        var rows=jdbc.queryForList("SELECT q.* FROM qc_inspection q WHERE q.tenant_id=? AND q.company_id=? AND q.id=?"+OrgOwnership.filter(actor,"q")+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()||!actor.allowsWarehouse(id(rows.getFirst(),"warehouse_id")))throw missing();
        return rows.getFirst();
    }
    private void state(Map<String,Object> row,String expected){if(!expected.equals(row.get("status")))throw conflict("检验单状态已变化");}
    private Map<String,Object> changed(CompanyAccess actor,long id,String action,Map<String,Object> old){
        var now=detail(actor,id);
        audit.record(actor,"qc_inspection."+action,"qc_inspection",id,Map.of("status",old.get("status")),Map.of("status",now.get("status"),"result",now.get("result")));
        return Map.of("id",Long.toString(id));
    }
    private Map<String,Object> page(List<Map<String,Object>> rows,int limit){
        var list=rows.stream().limit(limit).map(ApiRows::row).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",list);result.put("nextCursor",rows.size()>limit?list.getLast().get("id"):null);return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess actor,String action,String key,Object input,Supplier<Map<String,Object>> work){
        var reply=commands.execute(actor,"qc_inspection."+action,key,input,200,work,(Class)Map.class);
        return detail(actor,id(((Map<String,Object>)reply).get("id").toString()));
    }
}
