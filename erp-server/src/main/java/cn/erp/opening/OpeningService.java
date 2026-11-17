package cn.erp.opening;

import cn.erp.accounting.*;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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
public class OpeningService {
    private WorkflowPort workflow;
    @org.springframework.beans.factory.annotation.Autowired
    void workflow(WorkflowPort workflow) {this.workflow=workflow;}
    public record Stock(@Pattern(regexp="opening|gain|loss|block|unblock|scrap",message="库存调整类型无效") String adjustType,
        @NotNull(message="请选择仓库") Long warehouseId,@NotNull(message="请选择业务日期") LocalDate businessDate,
        @NotBlank(message="请填写调整原因") @Size(max=200,message="原因最多200字") String reason,
        @NotEmpty(message="请填写库存明细") @Size(max=200,message="每张调整单最多200行") List<@NotNull(message="商品行不能为空") @Valid StockLine> lines,Long version) {}
    public record StockLine(@NotNull(message="请选择商品") Long skuId,@NotNull(message="请选择单位") Long unitId,
        @NotBlank(message="请填写数量") String qty,String inputTotalCost) {}
    public record Entry(@NotBlank(message="请选择期初类型") @Pattern(regexp="receivable|payable|cash",message="期初类型无效") String entryType,
        Long partnerId,Long cashAccountId,@NotBlank(message="请选择借贷方向") @Pattern(regexp="D|C",message="借贷方向无效") String direction,
        @NotBlank(message="请填写期初金额") String amount,@NotNull(message="请选择业务日期") LocalDate businessDate,
        @Size(max=200,message="备注最多200字") String remark,Long version) {}
    public record Version(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
        @Size(max=200,message="意见最多200字") String reason,@Size(max=100,message="自选节点最多100个") Map<String,List<Long>> workflowAssignees) {
        public Version(Long version,String reason) {this(version,reason,null);}
    }
    private final JdbcTemplate jdbc;
    private final FinanceSupport support;
    private final ReferenceAvailability references;
    private final CatalogAvailability catalog;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final cn.erp.pricing.PricingEngine pricing;
    public OpeningService(JdbcTemplate jdbc,FinanceSupport support,ReferenceAvailability references,CatalogAvailability catalog,
            BusinessCommands commands,DocumentNumbers numbers,AuditService audit,InventoryService inventory,LedgerService ledger,SettlementService settlement,cn.erp.pricing.PricingEngine pricing) {
        this.jdbc=jdbc;this.support=support;this.references=references;this.catalog=catalog;this.commands=commands;
        this.numbers=numbers;this.audit=audit;this.inventory=inventory;this.ledger=ledger;this.settlement=settlement;this.pricing=pricing;
    }

    public Map<String,Object> createStock(CompanyAccess scope,String key,Stock input) {
        return execute(scope,"stock_adjustment.create",key,input,201,()->{
            validateStock(scope,input);long id=Ids.next();
            jdbc.update("INSERT INTO stock_adjustment(id,tenant_id,company_id,adjust_no,warehouse_id,adjust_type,reason,business_date,created_by,updated_by,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,scope.tenantId(),scope.companyId(),numbers.next(scope,"SA",input.businessDate()),input.warehouseId(),adjustType(input),input.reason(),input.businessDate(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            writeLines(scope,id,input.lines());return changed(scope,true,id,"create",null);
        });
    }
    public Map<String,Object> updateStock(CompanyAccess scope,long id,Stock input) {
        var before=find(scope,true,id,true);version(before,input.version());state(before,"draft");validateStock(scope,input);
        if(!before.get("adjust_type").equals(adjustType(input)))throw invalid("调整类型建单后不能修改");
        jdbc.update("UPDATE stock_adjustment SET warehouse_id=?,business_date=?,reason=?,version=version+1,updated_by=? WHERE id=?",
            input.warehouseId(),input.businessDate(),input.reason(),scope.userId(),id);
        jdbc.update("DELETE FROM stock_adjustment_line WHERE tenant_id=? AND company_id=? AND adjustment_id=?",scope.tenantId(),scope.companyId(),id);
        writeLines(scope,id,input.lines());return changed(scope,true,id,"update",before);
    }
    public Map<String,Object> createEntry(CompanyAccess scope,String key,Entry input) {
        return execute(scope,"opening_entry.create",key,input,201,()->{
            validateEntry(scope,input);long id=Ids.next();
            jdbc.update("""
                INSERT INTO opening_entry(id,tenant_id,company_id,entry_no,entry_type,partner_id,cash_account_id,direction,amount,business_date,remark,created_by,updated_by,owner_user_id,owner_department_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),numbers.next(scope,"OE",input.businessDate()),input.entryType(),input.partnerId(),input.cashAccountId(),
                input.direction(),amount(input.amount()),input.businessDate(),input.remark(),scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
            return changed(scope,false,id,"create",null);
        });
    }
    public Map<String,Object> updateEntry(CompanyAccess scope,long id,Entry input) {
        var before=find(scope,false,id,true);version(before,input.version());state(before,"draft");validateEntry(scope,input);
        jdbc.update("UPDATE opening_entry SET entry_type=?,partner_id=?,cash_account_id=?,direction=?,amount=?,business_date=?,remark=?,version=version+1,updated_by=? WHERE id=?",
            input.entryType(),input.partnerId(),input.cashAccountId(),input.direction(),amount(input.amount()),input.businessDate(),input.remark(),scope.userId(),id);
        return changed(scope,false,id,"update",before);
    }
    public Map<String,Object> command(CompanyAccess scope,boolean stock,long id,String action,String key,Version input) {
        return execute(scope,resource(stock)+"."+action,key,List.of(id,input),200,()->{
            if(stock) workflow.guard(scope,"stock_adjustment",id,action);
            var snapshot=find(scope,stock,id,false);
            Long period=Set.of("submit","approve","post").contains(action)?support.period(scope,((java.sql.Date)snapshot.get("business_date")).toLocalDate()):null;
            var before=find(scope,stock,id,true);version(before,input.version());
            String table=table(stock);
            switch(action) {
                case "submit" -> {if(!stock)throw invalid("往来期初直接送交审核");state(before,"draft");updateState(scope,table,id,"pending");workflow.start(scope,"stock_adjustment",id,input.workflowAssignees());}
                case "approve" -> {
                    state(before,stock?"pending":"draft");differentReviewer(scope,before);
                    jdbc.update("UPDATE "+table+" SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",scope.userId(),scope.userId(),id);
                }
                case "reject" -> {
                    if(!stock)throw invalid("该操作不适用于往来期初");state(before,"pending");differentReviewer(scope,before);
                    if(input.reason()==null||input.reason().isBlank())throw invalid("请填写驳回原因");
                    updateState(scope,table,id,"draft");
                }
                case "void" -> {state(before,"draft","pending","approved");updateState(scope,table,id,"voided");}
                case "post" -> {
                    state(before,"approved");
                    LocalDate day=((java.sql.Date)before.get("business_date")).toLocalDate();
                    ledger.lockOpenPeriod(scope,period,day);
                    if(stock)postStock(scope,id,before,period,day);else postEntry(scope,id,before,period,day);
                    jdbc.update("UPDATE "+table+" SET status='posted',period_id=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",period,scope.userId(),id);
                }
                default -> throw invalid("期初操作无效");
            }
            return changed(scope,stock,id,action,before);
        });
    }

    private void postStock(CompanyAccess scope,long id,Map<String,Object> head,long period,LocalDate day) {
        long warehouse=id(head,"warehouse_id");warehouse(scope,warehouse);
        var lines=jdbc.queryForList("SELECT * FROM stock_adjustment_line WHERE tenant_id=? AND company_id=? AND adjustment_id=? ORDER BY sku_id,id FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        inventory.lockKeys(scope,lines.stream().map(l->new cn.erp.inventory.InventoryModels.Key(warehouse,id(l,"sku_id"))).toList());
        var entries=new ArrayList<LedgerService.Line>();
        for(var line:lines) {
            long sku=id(line,"sku_id"),lineId=id(line,"id");catalog.requireActiveSku(scope,sku,id(line,"unit_id"));
            var qty=decimal(line,"base_qty");String type=head.get("adjust_type").toString();
            String movement=switch(type){case "opening"->"opening_in";case "gain"->"adjust_in";case "loss"->"adjust_out";default->type;};
            var posting=new cn.erp.inventory.InventoryModels.Posting(new cn.erp.inventory.InventoryModels.Key(warehouse,sku),
                new cn.erp.inventory.InventoryModels.Source("stock_adjustment",id,lineId),day,period,movement);
            BigDecimal unitCost,cost;
            if(Set.of("block","unblock").contains(type)) {
                inventory.freeze(scope,posting,qty,type.equals("block"));unitCost=BigDecimal.ZERO.setScale(8);cost=BigDecimal.ZERO.setScale(2);
            } else if(Set.of("opening","gain").contains(type)) {
                cost=decimal(line,"input_total_cost");var result=inventory.receive(scope,posting,qty,cost);unitCost=result.unitCost();
                addStockEntry(entries,"INVENTORY","D",cost,warehouse,sku,lineId);
                addStockEntry(entries,type.equals("opening")?"OPENING_EQUITY":"INVENTORY_ADJUSTMENT","C",cost,type.equals("opening")?null:warehouse,type.equals("opening")?null:sku,lineId);
            } else {
                var result=inventory.issue(scope,posting,qty,null);unitCost=result.unitCost();cost=result.movementCost().subtract(result.adjustment());
                addStockEntry(entries,"INVENTORY_ADJUSTMENT","D",result.movementCost(),warehouse,sku,lineId);
                addStockEntry(entries,"INVENTORY","C",result.movementCost(),warehouse,sku,lineId);
                addStockEntry(entries,"INVENTORY","D",result.adjustment(),warehouse,sku,lineId);
                addStockEntry(entries,"COST_VARIANCE","C",result.adjustment(),null,null,lineId);
            }
            jdbc.update("UPDATE stock_adjustment_line SET unit_cost=?,cost_amount=?,version=version+1,updated_by=? WHERE id=?",unitCost,cost,scope.userId(),lineId);
        }
        if(!entries.isEmpty())ledger.post(scope,new LedgerService.Posting("stock_adjustment",id,0,period,day,entries));
    }

    private void postEntry(CompanyAccess scope,long id,Map<String,Object> head,long period,LocalDate day) {
        String type=head.get("entry_type").toString(),direction=head.get("direction").toString();BigDecimal value=decimal(head,"amount");
        Long partner=head.get("partner_id")==null?null:id(head,"partner_id"),account=head.get("cash_account_id")==null?null:id(head,"cash_account_id");
        if(type.equals("cash"))references.requireCashAccount(scope,account);
        else {
            references.requirePartner(scope,partner,type.equals("receivable")?"customer":"supplier");
            boolean ar=type.equals("receivable");String fact=ar?"receivable":"payable",party=ar?"customer_id":"supplier_id";
            String kind=direction.equals(ar?"D":"C")?"charge":"credit";long factId=Ids.next();
            jdbc.update("INSERT INTO "+fact+"(id,tenant_id,company_id,"+party+",kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id,created_by) VALUES(?,?,?,?,?,'opening_entry',?,?,?,?,0,?,?,?)",
                factId,scope.tenantId(),scope.companyId(),partner,kind,id,id,value,value,day,period,scope.userId());
            settlement.open(scope,new SettlementService.OpenItem(fact,factId,partner,direction,value,day));
        }
        String accountCode=type.equals("cash")?"FUNDS":type.equals("receivable")?"AR":"AP";
        ledger.post(scope,new LedgerService.Posting("opening_entry",id,0,period,day,List.of(
            new LedgerService.Line(accountCode,direction,value,partner,account,null,null,id,"往来或资金期初"),
            new LedgerService.Line("OPENING_EQUITY",direction.equals("D")?"C":"D",value,null,null,null,null,id,"往来或资金期初"))));
    }

    public Map<String,Object> get(CompanyAccess scope,boolean stock,long id) {
        var raw=find(scope,stock,id,false);var result=row(raw);result.put("documentNo",raw.get(stock?"adjust_no":"entry_no"));
        if(stock) {
            result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("warehouse_id")));
            var lines=jdbc.queryForList("""
                SELECT l.*,s.name sku_name,u.name unit_name FROM stock_adjustment_line l
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.adjustment_id=? ORDER BY l.line_no
                """,scope.tenantId(),scope.companyId(),id).stream().map(FinanceSupport::row).toList();
            if(!scope.hasPermission("cost:view"))for(var line:lines){line.remove("inputTotalCost");line.remove("unitCost");line.remove("costAmount");}
            result.put("lines",lines);
        } else {
            if(raw.get("partner_id")!=null)result.put("partnerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("partner_id")));
            if(raw.get("cash_account_id")!=null)result.put("cashAccountName",jdbc.queryForObject("SELECT name FROM cash_account WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("cash_account_id")));
        }
        result.put("allowedActions",actions(scope,stock,raw));if(stock)WorkflowViews.decorate(jdbc,scope,"stock_adjustment",id,result);return result;
    }
    public Map<String,Object> list(CompanyAccess scope,boolean stock,String status,Long cursor,int limit) {
        return list(scope,stock,status,null,cursor,limit);
    }
    public Map<String,Object> list(CompanyAccess scope,boolean stock,String status,String adjustType,Long cursor,int limit) {
        long before=cursor(cursor,limit);if(status!=null&&!Set.of("draft","pending","approved","posted","voided","reversed").contains(status))throw invalid("单据状态无效");
        String sql="SELECT id FROM "+table(stock)+" WHERE tenant_id=? AND company_id=? AND id<?"+cn.erp.shared.OrgOwnership.filter(scope,table(stock));
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before));
        if(status!=null){sql+=" AND status=?";args.add(status);}
        if(adjustType!=null){if(!stock||!Set.of("opening","gain","loss","block","unblock","scrap").contains(adjustType))throw invalid("库存调整类型无效");sql+=" AND adjust_type=?";args.add(adjustType);}
        if(stock&&!scope.allWarehouses()) {
            if(scope.warehouseIds().isEmpty())return page(List.of(),limit);
            sql+=" AND warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";args.addAll(scope.warehouseIds());
        }
        args.add(limit+1);var rows=jdbc.queryForList(sql+" ORDER BY id DESC LIMIT ?",args.toArray());
        var items=rows.stream().limit(limit).map(r->get(scope,stock,id(r,"id"))).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return result;
    }
    private List<String> actions(CompanyAccess scope,boolean stock,Map<String,Object> head) {
        var actions=new ArrayList<String>();String state=head.get("status").toString(),resource=resource(stock);
        if(state.equals("draft")) {add(scope,actions,resource,"update");add(scope,actions,resource,stock?"submit":"approve",stock||id(head,"created_by")!=scope.userId());}
        if(stock&&state.equals("pending")&&id(head,"created_by")!=scope.userId()){add(scope,actions,resource,"approve");add(scope,actions,resource,"reject");}
        if(state.equals("approved"))add(scope,actions,resource,"post");
        if(Set.of("draft","pending","approved").contains(state))add(scope,actions,resource,"void");return stock?WorkflowViews.actions(head,actions):actions;
    }
    private void validateStock(CompanyAccess scope,Stock input) {
        date(input.businessDate());String type=adjustType(input);
        if(!Set.of("opening","gain","loss","block","unblock","scrap").contains(type))throw invalid("库存调整类型无效");
        warehouse(scope,input.warehouseId());if(input.lines().stream().map(StockLine::skuId).distinct().count()!=input.lines().size())throw invalid("同一商品不能重复录入");
        for(var line:input.lines()){catalog.requireActiveSku(scope,line.skuId(),line.unitId());quantity(line.qty());
            if(Set.of("opening","gain").contains(type))cost(line.inputTotalCost());
            else if(line.inputTotalCost()!=null)throw invalid("出库、冻结、解冻和报废成本由系统计算，请勿填写总成本");}
    }
    private void validateEntry(CompanyAccess scope,Entry input) {
        date(input.businessDate());amount(input.amount());
        if(input.entryType().equals("cash")) {if(input.partnerId()!=null||input.cashAccountId()==null||!input.direction().equals("D"))throw invalid("资金期初须选择账户和借方方向");references.requireCashAccount(scope,input.cashAccountId());}
        else {if(!Set.of("receivable","payable").contains(input.entryType())||input.cashAccountId()!=null||input.partnerId()==null)throw invalid("往来期初须选择对应往来单位");
            references.requirePartner(scope,input.partnerId(),input.entryType().equals("receivable")?"customer":"supplier");}
    }
    private void writeLines(CompanyAccess scope,long id,List<StockLine> lines) {
        int no=0;for(var line:lines) {
            BigDecimal rate=jdbc.queryForObject("SELECT rate_to_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",BigDecimal.class,scope.tenantId(),line.skuId(),line.unitId());
            BigDecimal base=new BigDecimal(pricing.convertQuantity(line.qty(),rate.toPlainString()));
            if(base.signum()<=0||base.precision()>24)throw invalid("换算后的数量超出允许范围");
            jdbc.update("INSERT INTO stock_adjustment_line(id,tenant_id,company_id,adjustment_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty,input_total_cost,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Ids.next(),scope.tenantId(),scope.companyId(),id,++no,line.skuId(),line.unitId(),rate,quantity(line.qty()),base,line.inputTotalCost()==null?null:cost(line.inputTotalCost()),scope.userId(),scope.userId());
        }
    }
    private Map<String,Object> find(CompanyAccess scope,boolean stock,long id,boolean lock) {
        cn.erp.shared.OrgOwnership.require(jdbc,scope,table(stock),id);
        var rows=jdbc.queryForList("SELECT * FROM "+table(stock)+" WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()||stock&&!scope.allowsWarehouse(id(rows.getFirst(),"warehouse_id")))throw missing();return rows.getFirst();
    }
    private void warehouse(CompanyAccess scope,long id) {
        if(!scope.allowsWarehouse(id)||jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId(),id)!=1)throw missing();
    }
    private void differentReviewer(CompanyAccess scope,Map<String,Object> row) {if(id(row,"created_by")==scope.userId())throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","单据必须由另一位操作人审核");}
    private void updateState(CompanyAccess scope,String table,long id,String state) {jdbc.update("UPDATE "+table+" SET status=?,version=version+1,updated_by=? WHERE id=?",state,scope.userId(),id);}
    private Map<String,Object> changed(CompanyAccess scope,boolean stock,long id,String action,Object before) {
        var permissions=new HashSet<>(scope.permissions());permissions.remove("cost:view");
        var result=get(new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),scope.allWarehouses(),scope.warehouseIds(),permissions,scope.orgScope()),stock,id);
        audit.record(scope,resource(stock)+"."+action,resource(stock),id,null,result);return result;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private Map<String,Object> execute(CompanyAccess scope,String operation,String key,Object payload,int status,Supplier<Map<String,Object>> work) {return commands.execute(scope,operation,key,payload,status,work,(Class)Map.class);}
    private static String table(boolean stock){return stock?"stock_adjustment":"opening_entry";}
    private static String resource(boolean stock){return stock?"stock_adjustment":"opening_entry";}
    private void add(CompanyAccess scope,List<String> actions,String resource,String action){add(scope,actions,resource,action,true);}
    private void add(CompanyAccess scope,List<String> actions,String resource,String action,boolean condition){if(condition&&scope.hasPermission(resource+":"+action))actions.add(action);}
    private BigDecimal cost(String value) {if(value==null||!value.matches("[0-9]{1,22}(?:\\.[0-9]{1,2})?"))throw invalid("总成本须为最多两位小数的非负金额");return new BigDecimal(value).setScale(2);}
    private static String adjustType(Stock input){return input.adjustType()==null?"opening":input.adjustType();}
    private void addStockEntry(List<LedgerService.Line> entries,String code,String direction,BigDecimal amount,Long warehouse,Long sku,long line){
        if(amount.signum()==0)return;
        entries.add(new LedgerService.Line(code,amount.signum()>0?direction:direction.equals("D")?"C":"D",amount.abs(),null,null,warehouse,sku,line,"库存调整"));
    }
}
