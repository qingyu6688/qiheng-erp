package cn.erp.stockmisc;

import cn.erp.accounting.LedgerService;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.catalog.CatalogAvailability.SkuUnit;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.*;
import cn.erp.inventory.InventoryService;
import cn.erp.pricing.PricingEngine;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.*;
import cn.erp.sysadmin.DictionaryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;
import static cn.erp.stockmisc.StockMiscModels.*;
import static cn.erp.stockmisc.StockMiscQueries.RESOURCE;

@Service
@Transactional
class StockMiscService {
    private final JdbcTemplate jdbc;
    private final StockMiscQueries queries;
    private final StockMiscLoans loans;
    private final CatalogAvailability catalog;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final PricingEngine pricing;
    private final FinanceSupport dates;
    private final DocumentNumbers numbers;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final DictionaryService dictionaries;

    StockMiscService(JdbcTemplate jdbc,StockMiscQueries queries,StockMiscLoans loans,CatalogAvailability catalog,
                     InventoryService inventory,LedgerService ledger,PricingEngine pricing,FinanceSupport dates,
                     DocumentNumbers numbers,BusinessCommands commands,AuditService audit,DictionaryService dictionaries){
        this.jdbc=jdbc;this.queries=queries;this.loans=loans;this.catalog=catalog;this.inventory=inventory;
        this.ledger=ledger;this.pricing=pricing;this.dates=dates;this.numbers=numbers;this.commands=commands;
        this.audit=audit;this.dictionaries=dictionaries;
    }

    @Transactional(readOnly=true)
    Map<String,Object> detail(CompanyAccess actor,long document){return queries.detail(actor,document);}

    @Transactional(readOnly=true)
    Map<String,Object> list(CompanyAccess actor,String direction,String status,Long cursor,int limit,String keyword,boolean loansOnly){
        return queries.list(actor,direction,status,cursor,limit,keyword,loansOnly);
    }

    @Transactional(readOnly=true)
    List<Map<String,Object>> outstanding(CompanyAccess actor,long loan){return queries.outstanding(actor,loan);}

    Map<String,Object> create(CompanyAccess actor,String key,Input input){
        return execute(actor,"create",key,input,201,()->{
            validate(actor,input);
            long document=Ids.next();
            jdbc.update("""
                    INSERT INTO stock_misc_doc(id,tenant_id,company_id,document_no,direction,purpose,warehouse_id,partner_id,income_expense_item_id,
                      business_date,expected_return_date,original_loan_id,remark,created_by,updated_by,owner_user_id,owner_department_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,document,actor.tenantId(),actor.companyId(),numbers.next(actor,input.direction().equals("in")?"MI":"MO",input.businessDate()),
                    input.direction(),input.purpose(),input.warehouseId(),input.partnerId(),input.incomeExpenseItemId(),input.businessDate(),
                    input.expectedReturnDate(),input.originalLoanId(),input.remark(),actor.userId(),actor.userId(),actor.userId(),OrgOwnership.department(actor));
            write(actor,document,input);
            changed(actor,document,"create",null,null);
            return document;
        });
    }

    Map<String,Object> update(CompanyAccess actor,long document,Input input){
        var before=queries.head(actor,document,true);
        version(before,input.version());state(before,"draft");validate(actor,input);
        if(!before.get("direction").equals(input.direction()))throw invalid("单据方向建单后不能更改");
        jdbc.update("""
                UPDATE stock_misc_doc SET purpose=?,warehouse_id=?,partner_id=?,income_expense_item_id=?,business_date=?,
                  expected_return_date=?,original_loan_id=?,remark=?,updated_by=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,input.purpose(),input.warehouseId(),input.partnerId(),input.incomeExpenseItemId(),input.businessDate(),
                input.expectedReturnDate(),input.originalLoanId(),input.remark(),actor.userId(),actor.tenantId(),actor.companyId(),document);
        jdbc.update("DELETE FROM stock_misc_line WHERE tenant_id=? AND company_id=? AND doc_id=?",actor.tenantId(),actor.companyId(),document);
        write(actor,document,input);
        return changed(actor,document,"update",before,null);
    }

    Map<String,Object> command(CompanyAccess actor,long document,String action,String key,Command input){
        queries.head(actor,document,false);
        return execute(actor,action,key,List.of(document,input),200,()->{
            var snapshot=queries.head(actor,document,false);
            LocalDate day=((Date)snapshot.get("business_date")).toLocalDate();
            Long period=Set.of("submit","approve","post").contains(action)?dates.period(actor,day):null;
            var current=queries.head(actor,document,true);
            version(current,input.version());
            switch(action){
                case "submit" -> submit(actor,document,current);
                case "approve" -> approve(actor,document,current);
                case "reject" -> reject(actor,document,current,input.reason());
                case "void" -> {state(current,"draft","pending","approved");transition(actor,document,"voided");}
                case "post" -> post(actor,document,current,period,day);
                default -> throw invalid("操作无效");
            }
            changed(actor,document,action,current,input.reason());
            return document;
        });
    }

    private void validate(CompanyAccess actor,Input input){
        date(input.businessDate());
        masterData(actor,input.warehouseId(),input.partnerId(),input.incomeExpenseItemId(),input.direction());
        dictionaries.validateValue(actor.tenantId(),"stock_misc_purpose",input.purpose(),false);
        boolean inbound=input.direction().equals("in");
        if(inbound&&Set.of("loan","sample","internal").contains(input.purpose())||
                !inbound&&Set.of("gift","loan_return").contains(input.purpose()))throw invalid("所选用途与出入库方向不符");
        switch(input.purpose()){
            case "loan" -> {
                if(input.partnerId()==null||input.expectedReturnDate()==null||input.expectedReturnDate().isBefore(input.businessDate())||input.originalLoanId()!=null)
                    throw invalid("借出必须指定往来单位及预计归还日期，日期不得早于业务日期");
            }
            case "loan_return" -> {
                if(input.originalLoanId()==null||input.expectedReturnDate()!=null)throw invalid("借出归还必须指定原借出单");
                loans.source(actor,input.originalLoanId(),input.partnerId(),input.businessDate(),true);
            }
            default -> {
                if(input.originalLoanId()!=null||input.expectedReturnDate()!=null)throw invalid("归还信息仅适用于借出业务");
            }
        }
    }

    private void masterData(CompanyAccess actor,long warehouse,Long partner,long item,String direction){
        if(!actor.allowsWarehouse(warehouse)||jdbc.queryForList("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active' FOR SHARE",
                Long.class,actor.tenantId(),actor.companyId(),warehouse).isEmpty())throw missing();
        if(partner!=null&&jdbc.queryForList("SELECT id FROM business_partner WHERE tenant_id=? AND id=? AND status='active' FOR SHARE",
                Long.class,actor.tenantId(),partner).isEmpty())throw missing();
        if(jdbc.queryForList("SELECT id FROM income_expense_item WHERE tenant_id=? AND id=? AND item_type=? AND status='active' FOR SHARE",
                Long.class,actor.tenantId(),item,direction.equals("in")?"income":"expense").isEmpty())throw invalid("收支项目不存在、已停用或与出入库方向不符");
    }

    private void write(CompanyAccess actor,long document,Input input){
        var keys=input.lines().stream().map(line->new SkuUnit(line.skuId(),line.unitId())).toList();
        var units=catalog.requireActiveSkus(actor,keys);
        var sources=new HashSet<Long>();
        int lineNo=0;
        for(var line:input.lines()){
            var key=new SkuUnit(line.skuId(),line.unitId());
            BigDecimal rate=decimal(units.get(key),"rate_to_base"),qty=quantity(line.qty());
            BigDecimal base=new BigDecimal(pricing.convertQuantity(line.qty(),rate.toPlainString()));
            var original=original(actor,input,line,sources,base);
            BigDecimal cost=cost(actor,input.direction(),line,original);
            String reason=cost!=null&&cost.signum()==0?(original==null?line.zeroCostReason().strip():"沿用原借出零成本"):null;
            var selection=original==null?line.stockSelection():loans.selection(original,line.stockSelection(),base);
            jdbc.update("""
                    INSERT INTO stock_misc_line(id,tenant_id,company_id,doc_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty,
                      input_unit_cost,zero_cost_reason,stock_selection,original_loan_line_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,Ids.next(),actor.tenantId(),actor.companyId(),document,++lineNo,line.skuId(),line.unitId(),rate,qty,base,cost,reason,
                    inventory.encodeSelection(selection),line.originalLoanLineId());
        }
    }

    private Map<String,Object> original(CompanyAccess actor,Input input,Line line,Set<Long> sources,BigDecimal base){
        if(input.originalLoanId()==null){
            if(line.originalLoanLineId()!=null)throw invalid("缺少原借出单");
            return null;
        }
        if(line.originalLoanLineId()==null||!sources.add(line.originalLoanLineId()))throw invalid("请选择未重复的原借出明细");
        var original=loans.line(actor,input.originalLoanId(),line.originalLoanLineId(),line.skuId());
        loans.verifyQuantity(actor,original,base,false);
        return original;
    }

    private BigDecimal cost(CompanyAccess actor,String direction,Line line,Map<String,Object> original){
        if(!direction.equals("in")){
            if(line.inputUnitCost()!=null||line.zeroCostReason()!=null)throw invalid("其他出库成本由系统计算");
            return null;
        }
        if(original!=null)return decimal(original,"unit_cost");
        if(!actor.hasPermission("cost:view"))throw new ApiException(403,"FORBIDDEN","登记其他入库成本需要成本权限");
        String value=line.inputUnitCost();
        if(value==null||!value.matches("[0-9]{1,16}(\\.[0-9]{1,8})?"))throw invalid("请输入单位成本，最多八位小数");
        var cost=new BigDecimal(value);
        if(cost.signum()==0&&(line.zeroCostReason()==null||line.zeroCostReason().isBlank()))throw invalid("零成本入库必须填写原因");
        return cost;
    }

    private void approve(CompanyAccess actor,long document,Map<String,Object> current){
        state(current,"pending");differentReviewer(actor,current);
        jdbc.update("""
                UPDATE stock_misc_doc SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),updated_by=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),document);
    }

    private void submit(CompanyAccess actor,long document,Map<String,Object> current){
        state(current,"draft");
        long warehouse=id(current,"warehouse_id");
        if("in".equals(current.get("direction")))for(var line:queries.lines(actor,document,false)){
            inventory.validateInbound(actor,new Key(warehouse,id(line,"sku_id")),decimal(line,"base_qty"),
                    inventory.decodeSelection(line.get("stock_selection")),null);
        }
        transition(actor,document,"pending");
    }

    private void reject(CompanyAccess actor,long document,Map<String,Object> current,String reason){
        state(current,"pending");differentReviewer(actor,current);
        if(reason==null||reason.isBlank())throw invalid("请填写驳回原因");
        transition(actor,document,"draft");
    }

    private static void differentReviewer(CompanyAccess actor,Map<String,Object> current){
        if(id(current,"created_by")==actor.userId())
            throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","审核人不能是单据经办人");
    }

    private void transition(CompanyAccess actor,long document,String status){
        jdbc.update("""
                UPDATE stock_misc_doc SET status=?,updated_by=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """,status,actor.userId(),actor.tenantId(),actor.companyId(),document);
    }

    private void post(CompanyAccess actor,long document,Map<String,Object> header,long period,LocalDate day){
        state(header,"approved");
        long warehouse=id(header,"warehouse_id");
        Long partner=header.get("partner_id")==null?null:id(header,"partner_id");
        String direction=header.get("direction").toString();
        masterData(actor,warehouse,partner,id(header,"income_expense_item_id"),direction);
        var loan=header.get("original_loan_id")==null?null:id(header,"original_loan_id");
        if(loan!=null)loans.source(actor,loan,partner,day,true);
        var lines=queries.lines(actor,document,true);
        catalog.requireActiveSkus(actor,lines.stream().map(line->new SkuUnit(id(line,"sku_id"),id(line,"unit_id"))).toList());
        inventory.lockKeys(actor,lines.stream().map(line->new Key(warehouse,id(line,"sku_id"))).distinct().sorted().toList());
        var entries=new ArrayList<LedgerService.Line>();
        boolean inbound=direction.equals("in");
        for(var line:lines)postLine(actor,document,warehouse,partner,loan,inbound,period,day,line,entries);
        if(!entries.isEmpty())ledger.post(actor,new LedgerService.Posting(RESOURCE,document,0,period,day,entries));
        jdbc.update("""
                UPDATE stock_misc_doc SET status='posted',period_id=?,posted_at=UTC_TIMESTAMP(3),posted_by=?,updated_by=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,period,actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),document);
    }

    private void postLine(CompanyAccess actor,long document,long warehouse,Long partner,Long loan,boolean inbound,long period,
                          LocalDate day,Map<String,Object> line,List<LedgerService.Line> entries){
        long sku=id(line,"sku_id"),lineId=id(line,"id");
        BigDecimal qty=decimal(line,"base_qty");
        var selection=inventory.decodeSelection(line.get("stock_selection"));
        if(loan!=null){
            long originalLine=id(line,"original_loan_line_id");
            var original=loans.line(actor,loan,originalLine,sku);
            loans.verifyQuantity(actor,original,qty,true);
            selection=loans.selection(original,selection,qty);
            loans.verifySerialOrigins(actor,loan,originalLine,sku,selection);
        }
        var posting=new Posting(new Key(warehouse,sku),new Source("stock_misc_line",document,lineId),day,period,
                !inbound?"stock_misc_out":loan==null?"stock_misc_in":"stock_misc_return_in");
        var cost=inbound
                ?inventory.receive(actor,posting,qty,qty.multiply(decimal(line,"input_unit_cost")).setScale(2,RoundingMode.HALF_UP),selection,null)
                :inventory.issue(actor,posting,qty,null,selection,null);
        addEntry(entries,"INVENTORY",inbound?"D":"C",cost.movementCost(),null,warehouse,sku,lineId);
        addEntry(entries,inbound?"MISC_STOCK_IN":"MISC_STOCK_OUT",inbound?"C":"D",cost.movementCost(),partner,warehouse,sku,lineId);
        addEntry(entries,"INVENTORY","D",cost.adjustment(),null,warehouse,sku,lineId);
        addEntry(entries,"COST_VARIANCE","C",cost.adjustment(),null,null,null,lineId);
        jdbc.update("UPDATE stock_misc_line SET unit_cost=?,amount=? WHERE tenant_id=? AND company_id=? AND id=?",
                cost.unitCost(),cost.movementCost().subtract(cost.adjustment()),actor.tenantId(),actor.companyId(),lineId);
    }

    private void addEntry(List<LedgerService.Line> entries,String account,String direction,BigDecimal amount,Long partner,
                          Long warehouse,Long sku,long line){
        if(amount.signum()==0)return;
        if(amount.signum()<0)direction=direction.equals("D")?"C":"D";
        entries.add(new LedgerService.Line(account,direction,amount.abs(),partner,null,warehouse,sku,line,null));
    }

    private Map<String,Object> changed(CompanyAccess actor,long document,String action,Map<String,Object> before,String reason){
        var after=queries.detail(actor,document);
        var metadata=new LinkedHashMap<String,Object>();
        metadata.put("status",after.get("status"));
        metadata.put("version",after.get("version"));
        if(reason!=null&&!reason.isBlank())metadata.put("reason",reason);
        audit.record(actor,RESOURCE+"."+action,RESOURCE,document,before==null?null:Map.of("status",before.get("status")),metadata);
        return after;
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private Map<String,Object> execute(CompanyAccess actor,String action,String key,Object payload,int code,Supplier<Long> work){
        var result=commands.execute(actor,RESOURCE+"."+action,key,payload,code,()->Map.of("id",Long.toString(work.get())),(Class)Map.class);
        return queries.detail(actor,Long.parseLong(((Map<String,Object>)result).get("id").toString()));
    }
}
