package cn.erp.stockcount;

import cn.erp.accounting.LedgerService;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.*;
import cn.erp.inventory.InventoryService;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;

@Service
@Transactional
public class StockCountService {
    private final JdbcTemplate jdbc;
    private final FinanceSupport finance;
    private final CatalogAvailability catalog;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    public StockCountService(JdbcTemplate jdbc,FinanceSupport finance,CatalogAvailability catalog,InventoryService inventory,
            LedgerService ledger,BusinessCommands commands,DocumentNumbers numbers,AuditService audit) {
        this.jdbc=jdbc;this.finance=finance;this.catalog=catalog;this.inventory=inventory;this.ledger=ledger;
        this.commands=commands;this.numbers=numbers;this.audit=audit;
    }

    public Map<String,Object> create(CompanyAccess scope,String key,StockCountModels.Document input) {
        return execute(scope,"create",key,input,201,()->{
            validate(scope,input);long id=Ids.next();
            jdbc.update("""
                INSERT INTO stock_count(id,tenant_id,company_id,count_no,warehouse_id,count_mode,business_date,remark,created_by,updated_by,owner_user_id,owner_department_id)
                VALUES(?,?,?,?,?,'watermark',?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),numbers.next(scope,"SC",input.businessDate()),input.warehouseId(),input.businessDate(),input.remark(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            writeLines(scope,id,input.lines());return changed(scope,id,"create");
        });
    }
    public Map<String,Object> update(CompanyAccess scope,long id,StockCountModels.Document input) {
        var before=find(scope,id,true);version(before,input.version());state(before,"draft");validate(scope,input);
        jdbc.update("UPDATE stock_count SET warehouse_id=?,business_date=?,remark=?,version=version+1,updated_by=? WHERE id=?",
            input.warehouseId(),input.businessDate(),input.remark(),scope.userId(),id);
        jdbc.update("DELETE FROM stock_count_line WHERE tenant_id=? AND company_id=? AND count_id=?",scope.tenantId(),scope.companyId(),id);
        writeLines(scope,id,input.lines());return changed(scope,id,"update");
    }
    public Map<String,Object> counts(CompanyAccess scope,long id,StockCountModels.Counts input) {
        var before=find(scope,id,true);version(before,input.version());state(before,"counting");
        var current=lines(scope,id,true);var requested=new HashSet<Long>();
        for(var line:input.lines())if(!requested.add(line.lineId()))throw invalid("同一盘点行不能重复录入");
        if(!requested.equals(new HashSet<>(current.stream().map(line->id(line,"id")).toList())))throw invalid("请逐行完整录入本单实盘数量");
        for(var line:input.lines()) {
            var counted=nonnegative(line.countedQty(),6,"实盘数量");
            var price=line.inputUnitCost()==null?null:nonnegative(line.inputUnitCost(),8,"盘盈录入单价");
            var original=current.stream().filter(item->id(item,"id")==line.lineId()).findFirst().orElseThrow(()->missing());
            var selected=inventory.decodeSelection(original.get("stock_selection"));
            if(line.serials()!=null||selected!=null)selected=new StockSelection(selected==null?null:selected.batchId(),null,null,null,
                    selected==null?null:selected.locationId(),line.serials());
            var difference=counted.subtract(decimal(original,"book_qty"));
            inventory.validateCountChange(scope,new Key(id(before,"warehouse_id"),id(original,"sku_id")),selected,difference);
            jdbc.update("UPDATE stock_count_line SET counted_qty=?,input_unit_cost=?,stock_selection=?,version=version+1,updated_by=? WHERE id=?",
                    counted,price,inventory.encodeSelection(selected),scope.userId(),line.lineId());
        }
        increment(scope,id);return changed(scope,id,"record_counts");
    }
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,StockCountModels.Version input) {
        return execute(scope,action,key,List.of(id,input),200,()->{
            var snapshot=find(scope,id,false);LocalDate day=((java.sql.Date)snapshot.get("business_date")).toLocalDate();
            Long period=Set.of("start","submit","approve","post").contains(action)?finance.period(scope,day):null;
            var before=find(scope,id,true);version(before,input.version());
            switch(action) {
                case "start" -> {state(before,"draft");start(scope,id,before);}
                case "submit" -> {state(before,"counting");requireCounts(scope,id,before);setState(scope,id,"submitted");}
                case "approve" -> {state(before,"submitted");differentReviewer(scope,before);
                    jdbc.update("UPDATE stock_count SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",scope.userId(),scope.userId(),id);}
                case "reject" -> {state(before,"submitted");differentReviewer(scope,before);if(input.reason()==null||input.reason().isBlank())throw invalid("请填写驳回原因");setState(scope,id,"counting");}
                case "void" -> {state(before,"draft","counting","submitted","approved");setState(scope,id,"voided");}
                case "post" -> {state(before,"approved");ledger.lockOpenPeriod(scope,period,day);post(scope,id,before,period,day);
                    jdbc.update("UPDATE stock_count SET status='posted',period_id=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",period,scope.userId(),id);}
                default -> throw invalid("盘点操作无效");
            }
            return changed(scope,id,action);
        });
    }
    private void start(CompanyAccess scope,long id,Map<String,Object> head) {
        long warehouse=id(head,"warehouse_id");warehouse(scope,warehouse);var details=lines(scope,id,true);
        inventory.lockKeys(scope,details.stream().map(line->new Key(warehouse,id(line,"sku_id"))).toList());
        // 同时锁住本次全部库存键，快照不受较小流水 ID 延迟提交的影响。
        long watermark=0;
        for(var line:details) {
            long sku=id(line,"sku_id");activeSku(scope,sku);
            var quantity=inventory.countQuantity(scope,new Key(warehouse,sku),inventory.decodeSelection(line.get("stock_selection")));
            jdbc.update("UPDATE stock_count_line SET book_qty=?,counted_qty=NULL,input_unit_cost=NULL,version=version+1,updated_by=? WHERE id=?",quantity,scope.userId(),line.get("id"));
            var movements=jdbc.queryForList("SELECT id FROM inventory_movement WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? ORDER BY id DESC LIMIT 1 FOR SHARE",Long.class,scope.tenantId(),scope.companyId(),warehouse,sku);
            if(!movements.isEmpty())watermark=Math.max(watermark,movements.getFirst());
        }
        jdbc.update("UPDATE stock_count SET status='counting',snapshot_movement_id=?,snapshot_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",watermark,scope.userId(),id);
    }
    private void requireCounts(CompanyAccess scope,long id,Map<String,Object> head) {
        for(var line:lines(scope,id,false)) {
            if(line.get("counted_qty")==null)throw invalid("所有盘点行都必须录入实盘数量");
            if(decimal(line,"diff_qty").signum()>0)gainUnitCost(scope,id(head,"warehouse_id"),line);
        }
    }
    private void post(CompanyAccess scope,long id,Map<String,Object> head,long period,LocalDate day) {
        long warehouse=id(head,"warehouse_id");warehouse(scope,warehouse);var details=lines(scope,id,true);
        inventory.lockKeys(scope,details.stream().map(line->new Key(warehouse,id(line,"sku_id"))).toList());
        var entries=new ArrayList<LedgerService.Line>();
        for(var line:details)postLine(scope,id,warehouse,line,period,day,entries);
        if(!entries.isEmpty())ledger.post(scope,new LedgerService.Posting("stock_count",id,0,period,day,entries));
    }
    private void postLine(CompanyAccess scope,long id,long warehouse,Map<String,Object> line,long period,LocalDate day,List<LedgerService.Line> entries) {
        long sku=id(line,"sku_id"),lineId=id(line,"id");activeSku(scope,sku);BigDecimal difference=decimal(line,"diff_qty");
        if(difference==null)throw invalid("盘点数量不完整");
        BigDecimal unitCost=BigDecimal.ZERO.setScale(8),cost=BigDecimal.ZERO.setScale(2);
        var selection=inventory.decodeSelection(line.get("stock_selection"));
        // 将快照差额加到当前余额，盘点期间发生的收发货不会被覆盖。
        if(difference.signum()>0) {
            unitCost=gainUnitCost(scope,warehouse,line);cost=difference.multiply(unitCost).setScale(2,RoundingMode.HALF_UP);
            inventory.receive(scope,new Posting(new Key(warehouse,sku),new Source("stock_count",id,lineId),day,period,"count_gain"),difference,cost,selection,null);
            entry(entries,"INVENTORY","D",cost,warehouse,sku,lineId);entry(entries,"INVENTORY_ADJUSTMENT","C",cost,warehouse,sku,lineId);
        } else if(difference.signum()<0) {
            var result=inventory.issue(scope,new Posting(new Key(warehouse,sku),new Source("stock_count",id,lineId),day,period,"count_loss"),difference.negate(),null,selection,null);
            unitCost=result.unitCost();cost=result.movementCost().subtract(result.adjustment());
            entry(entries,"INVENTORY_ADJUSTMENT","D",result.movementCost(),warehouse,sku,lineId);entry(entries,"INVENTORY","C",result.movementCost(),warehouse,sku,lineId);
            entry(entries,"INVENTORY","D",result.adjustment(),warehouse,sku,lineId);entry(entries,"COST_VARIANCE","C",result.adjustment(),null,null,lineId);
        }
        jdbc.update("UPDATE stock_count_line SET unit_cost=?,cost_amount=?,version=version+1,updated_by=? WHERE id=?",unitCost,cost,scope.userId(),lineId);
    }
    private BigDecimal gainUnitCost(CompanyAccess scope,long warehouse,Map<String,Object> line) {
        var cost=jdbc.queryForList("SELECT qty,total_cost,last_in_unit_cost FROM inventory_cost WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? FOR UPDATE",scope.tenantId(),scope.companyId(),warehouse,line.get("sku_id"));
        if(!cost.isEmpty()) {
            var current=cost.getFirst();BigDecimal qty=decimal(current,"qty");
            if(qty.signum()>0)return decimal(current,"total_cost").divide(qty,8,RoundingMode.HALF_UP);
            if(current.get("last_in_unit_cost")!=null)return decimal(current,"last_in_unit_cost");
        }
        if(line.get("input_unit_cost")==null)throw invalid("从未入库的盘盈商品请先填写盘盈单价，再提交审核");
        return decimal(line,"input_unit_cost");
    }

    @Transactional(readOnly=true)
    public Map<String,Object> get(CompanyAccess scope,long id) {
        var head=find(scope,id,false);var result=row(head);result.put("documentNo",head.get("count_no"));
        result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),head.get("warehouse_id")));
        var details=jdbc.queryForList("""
            SELECT l.*,s.name sku_name,u.name base_unit_name FROM stock_count_line l
            JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id
            JOIN unit u ON u.tenant_id=p.tenant_id AND u.id=p.base_unit_id
            WHERE l.tenant_id=? AND l.company_id=? AND l.count_id=? ORDER BY l.id
            """,scope.tenantId(),scope.companyId(),id).stream().map(line->{
                var view=row(line);view.put("stockSelection",inventory.viewSelection(line.get("stock_selection")));return view;
            }).toList();
        if(!scope.hasPermission("cost:view"))for(var line:details){line.remove("inputUnitCost");line.remove("unitCost");line.remove("costAmount");}
        result.put("lines",details);result.put("allowedActions",actions(scope,head));return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> list(CompanyAccess scope,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);if(status!=null&&!Set.of("draft","counting","submitted","approved","posted","voided").contains(status))throw invalid("盘点状态无效");
        String sql="SELECT id FROM stock_count WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"stock_count")+" AND id<?";var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        if(!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty())return page(List.of(),limit);
            sql+=" AND warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";args.addAll(scope.warehouseIds());
        }
        args.add(limit+1);var rows=jdbc.queryForList(sql+" ORDER BY id DESC LIMIT ?",args.toArray());
        var items=rows.stream().limit(limit).map(line->get(scope,id(line,"id"))).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return result;
    }
    private void validate(CompanyAccess scope,StockCountModels.Document input) {
        date(input.businessDate());warehouse(scope,input.warehouseId());
        if(!"watermark".equals(input.countMode()))throw invalid("本期使用流水水位盘点，暂不支持冻结方式");
        if(input.lines()==null||input.lines().isEmpty()||input.lines().size()>200)throw invalid("每张盘点单须有1到200行");
        if(input.lines().stream().map(line->List.of(line.skuId().toString(),lot(line.stockSelection()),position(line.stockSelection()))).distinct().count()!=input.lines().size())
            throw invalid("同一商品、批次及库位不能重复盘点");
        for(var line:input.lines())activeSku(scope,line.skuId());
    }
    private void writeLines(CompanyAccess scope,long id,List<StockCountModels.Item> lines) {
        for(var line:lines){
            var selection=line.stockSelection();
            if(selection!=null&&(selection.batchNo()!=null||selection.productionDate()!=null||selection.expiryDate()!=null))
                throw invalid("盘点须选择已有批次");
            jdbc.update("INSERT INTO stock_count_line(id,tenant_id,company_id,count_id,sku_id,batch_id,lot_key,location_key,stock_selection,book_qty,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,0,?,?)",
                    Ids.next(),scope.tenantId(),scope.companyId(),id,line.skuId(),selection==null?null:selection.batchId(),lot(selection),position(selection),
                    inventory.encodeSelection(selection),scope.userId(),scope.userId());
        }
    }

    private static String lot(StockSelection selection){return selection==null||selection.batchId()==null?"":selection.batchId().toString();}
    private static String position(StockSelection selection){return selection==null||selection.locationId()==null?"":selection.locationId().toString();}
    private long activeSku(CompanyAccess scope,long sku) {
        var units=jdbc.queryForList("SELECT p.base_unit_id FROM sku s JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id WHERE s.tenant_id=? AND s.id=?",Long.class,scope.tenantId(),sku);
        if(units.isEmpty())throw missing();catalog.requireActiveSku(scope,sku,units.getFirst());return units.getFirst();
    }
    private Map<String,Object> find(CompanyAccess scope,long id,boolean lock) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,"stock_count",id);
        var rows=jdbc.queryForList("SELECT * FROM stock_count WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"stock_count")+" AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()||!scope.allowsWarehouse(id(rows.getFirst(),"warehouse_id")))throw missing();return rows.getFirst();
    }
    private List<Map<String,Object>> lines(CompanyAccess scope,long id,boolean lock) {return jdbc.queryForList("SELECT * FROM stock_count_line WHERE tenant_id=? AND company_id=? AND count_id=? ORDER BY sku_id,id"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);}
    private void warehouse(CompanyAccess scope,long id) {if(!scope.allowsWarehouse(id)||jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId(),id)!=1)throw missing();}
    private void differentReviewer(CompanyAccess scope,Map<String,Object> head) {if(id(head,"created_by")==scope.userId())throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","盘点单必须由另一位操作人审核");}
    private void increment(CompanyAccess scope,long id) {jdbc.update("UPDATE stock_count SET version=version+1,updated_by=? WHERE id=?",scope.userId(),id);}
    private void setState(CompanyAccess scope,long id,String status) {jdbc.update("UPDATE stock_count SET status=?,version=version+1,updated_by=? WHERE id=?",status,scope.userId(),id);}
    private List<String> actions(CompanyAccess scope,Map<String,Object> head) {
        var actions=new ArrayList<String>();String state=head.get("status").toString();
        if(state.equals("draft")){add(scope,actions,"update");add(scope,actions,"start");}
        if(state.equals("counting")){add(scope,actions,"record_counts");add(scope,actions,"submit");}
        if(state.equals("submitted")&&id(head,"created_by")!=scope.userId()){add(scope,actions,"approve");add(scope,actions,"reject");}
        if(state.equals("approved"))add(scope,actions,"post");if(Set.of("draft","counting","submitted","approved").contains(state))add(scope,actions,"void");return actions;
    }
    private void add(CompanyAccess scope,List<String> actions,String action){if(scope.hasPermission("stock_count:"+action))actions.add(action);}
    private Map<String,Object> changed(CompanyAccess scope,long id,String action) {
        var permissions=new HashSet<>(scope.permissions());permissions.remove("cost:view");
        var result=get(new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),scope.allWarehouses(),scope.warehouseIds(),permissions,scope.orgScope()),id);
        audit.record(scope,"stock_count."+action,"stock_count",id,null,result);return result;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private Map<String,Object> execute(CompanyAccess scope,String action,String key,Object payload,int status,Supplier<Map<String,Object>> work){return commands.execute(scope,"stock_count."+action,key,payload,status,work,(Class)Map.class);}
    private BigDecimal nonnegative(String value,int scale,String name) {
        if(value==null||value.length()>34||!value.matches("[0-9]+(?:\\.[0-9]{1,"+scale+"})?"))throw invalid(name+"须为最多"+scale+"位小数的非负数");
        var number=new BigDecimal(value).setScale(scale);if(number.precision()>24)throw invalid(name+"超出允许范围");return number;
    }
    private void entry(List<LedgerService.Line> entries,String code,String direction,BigDecimal amount,Long warehouse,Long sku,long line) {
        if(amount.signum()!=0)entries.add(new LedgerService.Line(code,amount.signum()>0?direction:direction.equals("D")?"C":"D",amount.abs(),null,null,warehouse,sku,line,"盘点差异"));
    }
}
