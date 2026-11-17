package cn.erp.stocktransfer;

import cn.erp.accounting.LedgerService;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.TransferCosts;
import cn.erp.pricing.PricingEngine;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional
public class StockTransferService {
    private static final Set<String> STATES=Set.of("draft","submitted","shipped","received","received_with_diff","voided");
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final TransferCosts costs;
    private final LedgerService ledger;
    private final FinanceSupport finance;
    private final CatalogAvailability catalog;
    private final PricingEngine pricing;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    public StockTransferService(JdbcTemplate jdbc,InventoryService inventory,TransferCosts costs,LedgerService ledger,FinanceSupport finance,CatalogAvailability catalog,PricingEngine pricing,BusinessCommands commands,DocumentNumbers numbers,AuditService audit){
        this.jdbc=jdbc;this.inventory=inventory;this.costs=costs;this.ledger=ledger;this.finance=finance;this.catalog=catalog;this.pricing=pricing;this.commands=commands;this.numbers=numbers;this.audit=audit;
    }
    public Map<String,Object> create(CompanyAccess scope,String key,StockTransferModels.Input input){
        return execute(scope,"create",key,input,201,()->{validate(scope,input);long id=Ids.next();
            jdbc.update("INSERT INTO stock_transfer(id,tenant_id,company_id,transfer_no,from_warehouse_id,to_warehouse_id,business_date,remark,created_by,updated_by,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,scope.tenantId(),scope.companyId(),numbers.next(scope,"ST",input.businessDate()),input.fromWarehouseId(),input.toWarehouseId(),input.businessDate(),input.remark(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            writeLines(scope,id,input.lines());return changed(scope,id,"create");});
    }
    public Map<String,Object> update(CompanyAccess scope,long id,StockTransferModels.Input input){
        var before=find(scope,id,true);version(before,input.version());state(before,"draft");validate(scope,input);
        jdbc.update("UPDATE stock_transfer SET from_warehouse_id=?,to_warehouse_id=?,business_date=?,remark=?,version=version+1,updated_by=? WHERE id=?",input.fromWarehouseId(),input.toWarehouseId(),input.businessDate(),input.remark(),scope.userId(),id);
        jdbc.update("DELETE FROM stock_transfer_line WHERE tenant_id=? AND company_id=? AND transfer_id=?",scope.tenantId(),scope.companyId(),id);writeLines(scope,id,input.lines());return changed(scope,id,"update");
    }
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,StockTransferModels.Version input){
        return execute(scope,action,key,List.of(id,input),200,()->{
            var snapshot=find(scope,id,false);LocalDate day=day(snapshot,action.equals("submit")?null:input.businessDate());
            Long period=Set.of("submit","ship").contains(action)?finance.period(scope,day):null;
            var before=find(scope,id,true);version(before,input.version());if(!action.equals("void"))warehouses(scope,before);
            switch(action){
                case "submit" -> {state(before,"draft");setState(scope,id,"submitted");}
                case "void" -> {state(before,"draft","submitted");setState(scope,id,"voided");}
                case "ship" -> {state(before,"submitted");ship(scope,id,before,period,day);}
                default -> throw invalid("调拨操作无效");
            }
            return changed(scope,id,action);
        });
    }
    private void ship(CompanyAccess scope,long id,Map<String,Object> head,long period,LocalDate day){
        var lines=lines(scope,id,true);long from=id(head,"from_warehouse_id"),to=id(head,"to_warehouse_id");
        inventory.lockKeys(scope,keys(lines,from,to));var entries=new ArrayList<LedgerService.Line>();
        for(var line:lines){
            long lineId=id(line,"id"),sku=id(line,"sku_id");catalog.requireActiveSku(scope,sku,id(line,"unit_id"));
            var result=inventory.issue(scope,new Posting(new Key(from,sku),new Source("stock_transfer",id,lineId),day,period,"transfer_out"),
                    decimal(line,"base_qty"),null,inventory.decodeSelection(line.get("stock_selection")),null);
            jdbc.update("UPDATE stock_transfer_line SET unit_cost=?,cost_amount=?,version=version+1,updated_by=? WHERE id=?",result.unitCost(),result.movementCost(),scope.userId(),lineId);
            event(scope,id,lineId,"ship",decimal(line,"base_qty"),result.movementCost(),period,day,null);
            add(entries,"GOODS_IN_TRANSIT","D",result.movementCost(),from,sku,lineId);add(entries,"INVENTORY","C",result.movementCost(),from,sku,lineId);
            add(entries,"INVENTORY","D",result.adjustment(),from,sku,lineId);add(entries,"COST_VARIANCE","C",result.adjustment(),null,null,lineId);
        }
        post(scope,"stock_transfer_ship",id,0,period,day,entries);
        jdbc.update("UPDATE stock_transfer SET status='shipped',ship_period_id=?,shipped_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",period,scope.userId(),id);
    }
    public Map<String,Object> receive(CompanyAccess scope,long id,String key,StockTransferModels.Receive input){
        return execute(scope,"receive",key,List.of(id,input),200,()->{
            var snapshot=find(scope,id,false);LocalDate day=day(snapshot,input.businessDate());long period=finance.period(scope,day);
            var head=find(scope,id,true);version(head,input.version());state(head,"shipped");warehouses(scope,head);eventDate(scope,id,day);
            var lines=lines(scope,id,true);var selected=received(input.lines(),lines);long from=id(head,"from_warehouse_id"),to=id(head,"to_warehouse_id");
            inventory.lockKeys(scope,keys(lines,from,to));var entries=new ArrayList<LedgerService.Line>();boolean any=false;
            for(var line:lines){
                var received=selected.get(id(line,"id"));
                if(received==null||received.quantity().signum()==0)continue;
                any=true;
                receiveLine(scope,id,line,received,from,to,period,day,entries);
            }
            boolean last=!Boolean.FALSE.equals(input.finalReceipt());if(!last&&!any)throw invalid("分批调入须填写大于零的实收数量");
            if(last)markDifferences(scope,id);
            post(scope,"stock_transfer_receive",id,Math.toIntExact(input.version()),period,day,entries);refresh(scope,id,period);
            return changed(scope,id,"receive");
        });
    }
    private void receiveLine(CompanyAccess scope,long id,Map<String,Object> line,Received received,long from,long to,long period,LocalDate day,List<LedgerService.Line> entries){
        var qty=received.quantity();
        long lineId=id(line,"id"),sku=id(line,"sku_id");var remaining=costs.remaining(scope,lineId);
        if(qty.compareTo(remaining.remainingQuantity())>0)throw new ApiException(422,"RECEIVE_QTY_EXCEEDED","实收数量超过剩余在途数量");
        BigDecimal cost=qty.compareTo(remaining.remainingQuantity())==0?remaining.remainingCost():decimal(line,"cost_amount").multiply(decimal(line,"received_base_qty").add(qty)).divide(decimal(line,"base_qty"),2,RoundingMode.HALF_UP).subtract(decimal(line,"received_cost_amount"));
        long event=event(scope,id,lineId,"receive",qty,cost,period,day,null);
        var target=new Key(to,sku);
        var selection=inventory.returnSelection(scope,target,new Source("stock_transfer",id,lineId),"transfer_out",received.selection(),qty,null);
        inventory.receive(scope,new Posting(target,new Source("stock_transfer_event",id,event),day,period,"transfer_in"),qty,cost,selection,null);
        costs.receive(scope,lineId,remaining.version(),qty,cost);
        add(entries,"INVENTORY","D",cost,to,sku,event);add(entries,"GOODS_IN_TRANSIT","C",cost,from,sku,event);
    }
    public Map<String,Object> difference(CompanyAccess scope,long id,String key,StockTransferModels.Difference input){
        return execute(scope,"resolve_diff",key,List.of(id,input),200,()->{
            if(input.reason()==null||input.reason().isBlank()||input.reason().length()>200)throw invalid("请填写200字以内的差异处理原因");
            var snapshot=find(scope,id,false);LocalDate day=day(snapshot,input.businessDate());long period=finance.period(scope,day);
            var head=find(scope,id,true);version(head,input.version());state(head,"received_with_diff");warehouses(scope,head);eventDate(scope,id,day);
            var lines=lines(scope,id,true);var selected=differences(input.lines(),lines);long from=id(head,"from_warehouse_id"),to=id(head,"to_warehouse_id");
            inventory.lockKeys(scope,keys(lines,from,to));var entries=new ArrayList<LedgerService.Line>();
            for(var line:lines){String disposition=selected.get(id(line,"id"));if(disposition!=null)resolveLine(scope,id,line,disposition,from,period,day,input.reason(),entries);}
            post(scope,"stock_transfer_difference",id,Math.toIntExact(input.version()),period,day,entries);refresh(scope,id,period);return changed(scope,id,"resolve_diff");
        });
    }
    private void resolveLine(CompanyAccess scope,long id,Map<String,Object> line,String disposition,long from,long period,LocalDate day,String reason,List<LedgerService.Line> entries){
        stateValue(line,"diff_status","pending");long lineId=id(line,"id"),sku=id(line,"sku_id");var remaining=costs.remaining(scope,lineId);
        BigDecimal qty=decimal(line,"diff_base_qty"),cost=remaining.remainingCost();long event=event(scope,id,lineId,disposition,qty,cost,period,day,reason);
        var origin=new Source("stock_transfer",id,lineId);
        var key=new Key(from,sku);
        var selection=inventory.returnSelection(scope,key,origin,"transfer_out",null,qty,null);
        if(disposition.equals("returned"))inventory.receive(scope,new Posting(key,new Source("stock_transfer_event",id,event),day,period,"transfer_in"),qty,cost,selection,null);
        else inventory.finishLostInTransit(scope,key,event,selection);
        costs.resolveDifference(scope,lineId,remaining.version(),qty,cost);
        add(entries,disposition.equals("returned")?"INVENTORY":"INVENTORY_ADJUSTMENT","D",cost,from,sku,event);add(entries,"GOODS_IN_TRANSIT","C",cost,from,sku,event);
    }
    private void markDifferences(CompanyAccess scope,long id){
        jdbc.update("UPDATE stock_transfer_line SET diff_base_qty=base_qty-received_base_qty,diff_status=IF(base_qty=received_base_qty,'none','pending'),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND transfer_id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);
    }
    private void refresh(CompanyAccess scope,long id,long period){
        long pending=jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfer_line WHERE tenant_id=? AND company_id=? AND transfer_id=? AND diff_status='pending'",Long.class,scope.tenantId(),scope.companyId(),id);
        long remaining=jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfer_line WHERE tenant_id=? AND company_id=? AND transfer_id=? AND base_qty>received_base_qty+IF(diff_status='resolved',diff_base_qty,0)",Long.class,scope.tenantId(),scope.companyId(),id);
        String status=pending>0?"received_with_diff":remaining==0?"received":"shipped";
        jdbc.update("UPDATE stock_transfer SET status=?,receive_period_id=?,received_at=IF(?='shipped',NULL,UTC_TIMESTAMP(3)),version=version+1,updated_by=? WHERE id=?",status,period,status,scope.userId(),id);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(CompanyAccess scope,long id){
        var raw=find(scope,id,false);var result=row(raw);result.put("documentNo",raw.get("transfer_no"));
        result.put("fromWarehouseName",warehouseName(scope,id(raw,"from_warehouse_id")));result.put("toWarehouseName",warehouseName(scope,id(raw,"to_warehouse_id")));
        var lines=jdbc.queryForList("SELECT l.*,s.name sku_name,u.name unit_name FROM stock_transfer_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id WHERE l.tenant_id=? AND l.company_id=? AND l.transfer_id=? ORDER BY l.line_no",scope.tenantId(),scope.companyId(),id).stream().map(line->{
            var view=row(line);view.put("stockSelection",inventory.viewSelection(line.get("stock_selection")));return view;
        }).toList();
        for(var line:lines){BigDecimal remaining=new BigDecimal(line.get("baseQty").toString()).subtract(new BigDecimal(line.get("receivedBaseQty").toString()));if("resolved".equals(line.get("diffStatus")))remaining=remaining.subtract(new BigDecimal(line.get("diffBaseQty").toString()));line.put("remainingQty",remaining.toPlainString());if(!scope.hasPermission("cost:view"))hideCost(line);}
        var events=jdbc.queryForList("SELECT * FROM stock_transfer_event_line WHERE tenant_id=? AND company_id=? AND transfer_id=? ORDER BY id",scope.tenantId(),scope.companyId(),id).stream().map(FinanceSupport::row).toList();
        if(!scope.hasPermission("cost:view"))events.forEach(StockTransferService::hideCost);result.put("lines",lines);result.put("events",events);result.put("allowedActions",actions(scope,raw));return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(CompanyAccess scope,String status,Long cursor,int limit){
        long before=cursor(cursor,limit);if(status!=null&&!STATES.contains(status))throw invalid("调拨状态无效");
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before));String sql="SELECT id FROM stock_transfer WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"stock_transfer")+" AND id<?";
        if(status!=null){sql+=" AND status=?";args.add(status);}if(!scope.allWarehouses()){if(scope.warehouseIds().isEmpty())return page(List.of(),limit);String slots=String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"));sql+=" AND from_warehouse_id IN ("+slots+") AND to_warehouse_id IN ("+slots+")";args.addAll(scope.warehouseIds());args.addAll(scope.warehouseIds());}
        args.add(limit+1);var ids=jdbc.queryForList(sql+" ORDER BY id DESC LIMIT ?",Long.class,args.toArray());var items=ids.stream().limit(limit).map(id->get(scope,id)).toList();var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",ids.size()>limit?items.getLast().get("id"):null);return result;
    }
    private List<String> actions(CompanyAccess scope,Map<String,Object> head){
        List<String> possible=switch(head.get("status").toString()){case "draft"->List.of("update","submit","void");case "submitted"->List.of("ship","void");case "shipped"->List.of("receive");case "received_with_diff"->List.of("resolve_diff");default->List.of();};return possible.stream().filter(a->scope.hasPermission("stock_transfer:"+a)).toList();
    }
    private void validate(CompanyAccess scope,StockTransferModels.Input input){
        if(input==null||input.fromWarehouseId()==null||input.toWarehouseId()==null||input.lines()==null||input.lines().isEmpty()||input.lines().size()>200)throw invalid("请选择仓库并填写1到200行明细");
        date(input.businessDate());if(input.fromWarehouseId().equals(input.toWarehouseId()))throw invalid("调出和调入仓库不能相同");warehouse(scope,input.fromWarehouseId());warehouse(scope,input.toWarehouseId());
        if(input.remark()!=null&&input.remark().length()>500)throw invalid("备注最多500字");if(input.lines().stream().anyMatch(Objects::isNull))throw invalid("调拨明细不能为空");if(input.lines().stream().map(StockTransferModels.Line::skuId).distinct().count()!=input.lines().size())throw invalid("同一商品不能重复录入");
        for(var line:input.lines()){if(line==null||line.skuId()==null||line.unitId()==null)throw invalid("请选择商品和单位");catalog.requireActiveSku(scope,line.skuId(),line.unitId());quantity(line.qty());}
    }
    private void writeLines(CompanyAccess scope,long id,List<StockTransferModels.Line> lines){int no=0;for(var line:lines){
        var rate=jdbc.queryForObject("SELECT rate_to_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",BigDecimal.class,scope.tenantId(),line.skuId(),line.unitId());var base=new BigDecimal(pricing.convertQuantity(line.qty(),rate.toPlainString()));
        jdbc.update("INSERT INTO stock_transfer_line(id,tenant_id,company_id,transfer_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty,stock_selection,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",Ids.next(),scope.tenantId(),scope.companyId(),id,++no,line.skuId(),line.unitId(),rate,quantity(line.qty()),base,inventory.encodeSelection(line.stockSelection()),scope.userId(),scope.userId());}}
    private Map<String,Object> find(CompanyAccess scope,long id,boolean lock){
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"stock_transfer",id);var rows=jdbc.queryForList("SELECT * FROM stock_transfer WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"stock_transfer")+" AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);if(rows.isEmpty())throw missing();var row=rows.getFirst();if(!scope.allowsWarehouse(id(row,"from_warehouse_id"))||!scope.allowsWarehouse(id(row,"to_warehouse_id")))throw missing();return row;}
    private List<Map<String,Object>> lines(CompanyAccess scope,long id,boolean lock){return jdbc.queryForList("SELECT * FROM stock_transfer_line WHERE tenant_id=? AND company_id=? AND transfer_id=? ORDER BY sku_id,id"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);}
    private void warehouse(CompanyAccess scope,long id){if(!scope.allowsWarehouse(id)||jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId(),id)!=1)throw missing();}
    private void warehouses(CompanyAccess scope,Map<String,Object> head){warehouse(scope,id(head,"from_warehouse_id"));warehouse(scope,id(head,"to_warehouse_id"));}
    private String warehouseName(CompanyAccess scope,long id){return jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),id);}
    private List<Key> keys(List<Map<String,Object>> lines,long from,long to){var keys=new ArrayList<Key>();for(var line:lines){keys.add(new Key(from,id(line,"sku_id")));keys.add(new Key(to,id(line,"sku_id")));}return keys;}
    private LocalDate day(Map<String,Object> head,LocalDate requested){LocalDate day=requested==null?((java.sql.Date)head.get("business_date")).toLocalDate():requested;date(day);if(day.isBefore(((java.sql.Date)head.get("business_date")).toLocalDate()))throw invalid("操作日期不能早于调拨业务日期");return day;}
    private void eventDate(CompanyAccess scope,long id,LocalDate day){var latest=jdbc.queryForObject("SELECT MAX(business_date) FROM stock_transfer_event_line WHERE tenant_id=? AND company_id=? AND transfer_id=?",java.sql.Date.class,scope.tenantId(),scope.companyId(),id);if(latest!=null&&day.isBefore(latest.toLocalDate()))throw invalid("操作日期不能早于已有调拨记录");}
    private long event(CompanyAccess scope,long id,long line,String type,BigDecimal qty,BigDecimal cost,long period,LocalDate day,String reason){long event=Ids.next();jdbc.update("INSERT INTO stock_transfer_event_line(id,tenant_id,company_id,transfer_id,transfer_line_id,event_type,base_qty,cost_amount,business_date,period_id,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",event,scope.tenantId(),scope.companyId(),id,line,type,qty,cost,day,period,reason,scope.userId());return event;}
    private record Received(BigDecimal quantity,StockSelection selection) {}
    private Map<Long,Received> received(List<StockTransferModels.ReceivedLine> selected,List<Map<String,Object>> lines){if(selected==null||selected.isEmpty()||selected.size()>200)throw invalid("请填写1到200行实收明细");var ids=lines.stream().map(l->id(l,"id")).toList();var result=new HashMap<Long,Received>();for(var line:selected){if(line==null||line.lineId()==null||!ids.contains(line.lineId()))throw missing();var receive=new Received(nonNegative(line.qty()),line.stockSelection());if(result.put(line.lineId(),receive)!=null)throw invalid("实收行不能重复");}return result;}
    private Map<Long,String> differences(List<StockTransferModels.DifferenceLine> selected,List<Map<String,Object>> lines){if(selected==null||selected.isEmpty()||selected.size()>200)throw invalid("请选择1到200行差异");var ids=lines.stream().map(l->id(l,"id")).toList();var result=new HashMap<Long,String>();for(var line:selected){if(line==null||line.lineId()==null||!ids.contains(line.lineId()))throw missing();if(line.disposition()==null||!Set.of("returned","lost").contains(line.disposition()))throw invalid("差异处理方式无效");if(result.put(line.lineId(),line.disposition())!=null)throw invalid("差异行不能重复");}return result;}
    private BigDecimal nonNegative(String value){if(value==null||value.length()>32||!value.matches("[0-9]+(?:\\.[0-9]{1,6})?"))throw invalid("实收数量须为最多六位小数的非负数");var result=new BigDecimal(value).setScale(6);if(result.precision()>24)throw invalid("实收数量超出允许范围");return result;}
    private void stateValue(Map<String,Object> row,String field,String expected){if(!expected.equals(row.get(field)))throw new ApiException(409,"STATE_CONFLICT","该调拨行没有待处理差异");}
    private void setState(CompanyAccess scope,long id,String value){jdbc.update("UPDATE stock_transfer SET status=?,version=version+1,updated_by=? WHERE id=?",value,scope.userId(),id);}
    private static void hideCost(Map<String,Object> row){for(String field:List.of("unitCost","costAmount","receivedCostAmount","diffCostAmount"))row.remove(field);}
    private void add(List<LedgerService.Line> lines,String code,String direction,BigDecimal value,Long warehouse,Long sku,long line){if(value.signum()==0)return;lines.add(new LedgerService.Line(code,value.signum()>0?direction:direction.equals("D")?"C":"D",value.abs(),null,null,warehouse,sku,line,"仓库调拨"));}
    private void post(CompanyAccess scope,String type,long id,int revision,long period,LocalDate day,List<LedgerService.Line> entries){for(int start=0,segment=0;start<entries.size();start+=1000,segment++){int end=Math.min(start+1000,entries.size());ledger.post(scope,new LedgerService.Posting(type,id,Math.addExact(Math.multiplyExact(revision,10),segment),period,day,entries.subList(start,end)));}}
    private Map<String,Object> changed(CompanyAccess scope,long id,String action){var permissions=new HashSet<>(scope.permissions());permissions.remove("cost:view");var result=get(new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),scope.allWarehouses(),scope.warehouseIds(),permissions,scope.orgScope()),id);audit.record(scope,"stock_transfer."+action,"stock_transfer",id,null,result);return result;}
    @SuppressWarnings({"unchecked","rawtypes"}) private Map<String,Object> execute(CompanyAccess scope,String action,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work){return commands.execute(scope,"stock_transfer."+action,key,payload,status,work,(Class)Map.class);}
}
