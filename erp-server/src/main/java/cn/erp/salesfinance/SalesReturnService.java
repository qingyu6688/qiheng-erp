package cn.erp.salesfinance;

import cn.erp.accounting.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.pricing.PricingEngine;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.shared.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;
import static cn.erp.inventory.InventoryModels.*;

@Service
@Transactional(readOnly=true)
public class SalesReturnService {
    private final JdbcTemplate jdbc;
    private final FinanceSupport support;
    private final DocumentNumbers numbers;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ReferenceAvailability references;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final InventoryService inventory;
    private final PricingEngine pricing;
    private final SalesOrderQueries orders;
    public SalesReturnService(JdbcTemplate jdbc,FinanceSupport support,DocumentNumbers numbers,BusinessCommands commands,AuditService audit,
            ReferenceAvailability references,LedgerService ledger,SettlementService settlement,InventoryService inventory,PricingEngine pricing,SalesOrderQueries orders) {
        this.jdbc=jdbc;this.support=support;this.numbers=numbers;this.commands=commands;this.audit=audit;this.references=references;
        this.ledger=ledger;this.settlement=settlement;this.inventory=inventory;this.pricing=pricing;this.orders=orders;
    }

    public Map<String,Object> list(CompanyAccess scope,Long customer,String status,Long cursor,int limit) {
        long before=cursor(cursor,limit);
        if(status!=null&&!Set.of("draft","submitted","posted","reversed","voided").contains(status)) {throw invalid("退货状态无效");}
        var rows=jdbc.queryForList("""
                SELECT r.id,r.return_no document_no,r.customer_id,p.name customer_name,r.warehouse_id,w.name warehouse_name,
                r.business_date,r.credit_amount,r.status,r.version FROM sales_return r
                JOIN business_partner p ON p.tenant_id=r.tenant_id AND p.id=r.customer_id
                JOIN warehouse w ON w.tenant_id=r.tenant_id AND w.company_id=r.company_id AND w.id=r.warehouse_id
                WHERE r.tenant_id=? AND r.company_id=? AND r.id<? AND (? IS NULL OR r.customer_id=?)
                AND (? IS NULL OR r.status=?) AND
                """+warehouseFilter(scope)+" ORDER BY r.id DESC LIMIT ?",scope.tenantId(),scope.companyId(),before,customer,customer,status,status,limit+1);
        var result=page(rows,limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)result.get("items");
        for(int i=0;i<items.size();i++) {items.get(i).put("allowedActions",actions(scope,rows.get(i)));}
        return result;
    }

    private String warehouseFilter(CompanyAccess scope) {
        if(scope.allWarehouses()) {return "1=1";}
        if(scope.warehouseIds().isEmpty()) {return "1=0";}
        return "r.warehouse_id IN("+String.join(",",scope.warehouseIds().stream().sorted().map(String::valueOf).toList())+")";
    }

    private Map<String,Object> header(CompanyAccess scope,long id,boolean lock) {
        var row=support.find(scope,"sales_return",id,lock);
        if(!scope.allowsWarehouse(id(row,"warehouse_id"))) {throw missing();}
        return row;
    }

    public Map<String,Object> get(CompanyAccess scope,long id) {
        var raw=header(scope,id,false);var result=row(raw);result.put("documentNo",raw.get("return_no"));
        result.put("customerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("customer_id")));
        result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),raw.get("warehouse_id")));
        var lines=jdbc.queryForList("""
                SELECT l.*,s.name sku_name,u.name unit_name FROM sales_return_line l
                JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.return_id=? ORDER BY l.line_no
                """,scope.tenantId(),scope.companyId(),id).stream().map(FinanceSupport::row).toList();
        result.put("lines",lines);
        if(!scope.hasPermission("cost:view")) {
            result.remove("costAmount");lines.forEach(line->{line.remove("unitCost");line.remove("costAmount");});
        }
        result.put("allowedActions",actions(scope,raw));return result;
    }

    private List<String> actions(CompanyAccess scope,Map<String,Object> raw) {
        var actions=new ArrayList<String>();String state=raw.get("status").toString();
        if(state.equals("draft")) {
            if(scope.hasPermission("sales_return:update")) {actions.add("update");}
            if(scope.hasPermission("sales_return:submit")) {actions.add("submit");}
        } else if(state.equals("submitted")) {
            if(scope.hasPermission("sales_return:void")) {actions.add("void");}
            if(scope.hasPermission("sales_return:post")) {actions.add("post");}
        }
        return actions;
    }

    @Transactional
    public Map<String,Object> create(CompanyAccess scope,String key,FinanceModels.ReturnInput input) {
        return execute(scope,"sales_return.create",key,input,201,()->{
            validateHeader(scope,input);
            long id=Ids.next();String no=numbers.next(scope,"SR",YearMonth.from(input.businessDate()));
            jdbc.update("INSERT INTO sales_return(id,tenant_id,company_id,return_no,customer_id,warehouse_id,business_date,reason,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,scope.tenantId(),scope.companyId(),no,input.customerId(),input.warehouseId(),input.businessDate(),input.reason(),scope.userId(),scope.userId());
            saveLines(scope,id,input);
            audit.record(scope,"sales_return.create","sales_return",id,null,Map.of("documentNo",no));return safe(get(scope,id));
        });
    }

    @Transactional
    public Map<String,Object> update(CompanyAccess scope,long id,FinanceModels.ReturnInput input) {
        var before=header(scope,id,true);version(before,input.version());state(before,"draft");validateHeader(scope,input);
        jdbc.update("DELETE FROM sales_return_line WHERE tenant_id=? AND company_id=? AND return_id=?",scope.tenantId(),scope.companyId(),id);
        jdbc.update("UPDATE sales_return SET customer_id=?,warehouse_id=?,business_date=?,reason=?,version=version+1,updated_by=? WHERE id=?",
                input.customerId(),input.warehouseId(),input.businessDate(),input.reason(),scope.userId(),id);
        saveLines(scope,id,input);
        audit.record(scope,"sales_return.update","sales_return",id,null,Map.of("version",input.version()+1));return get(scope,id);
    }

    private void validateHeader(CompanyAccess scope,FinanceModels.ReturnInput input) {
        date(input.businessDate());references.requirePartner(scope,input.customerId(),"customer");
        if(!scope.allowsWarehouse(input.warehouseId())||jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId(),input.warehouseId())!=1) {throw missing();}
        if(input.lines()==null||input.lines().isEmpty()||input.lines().size()>500) {throw invalid("退货明细须有1到500行");}
        if(input.lines().stream().map(FinanceModels.ReturnLine::shipmentLineId).distinct().count()!=input.lines().size()) {throw invalid("原发货行不能重复");}
    }

    private Map<String,Object> source(CompanyAccess scope,long id,boolean lock) {
        var rows=jdbc.queryForList("""
                SELECT l.*,h.customer_id,h.warehouse_id,h.sign_date,h.status shipment_status,o.order_id
                FROM shipment_line l JOIN shipment h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.shipment_id
                JOIN sales_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.id=?
                """+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()||!scope.allowsWarehouse(id(rows.getFirst(),"warehouse_id"))) {throw missing();}
        var row=rows.getFirst();
        if(!Set.of("signed","signed_with_diff").contains(row.get("shipment_status"))) {throw new ApiException(409,"STATE_CONFLICT","只有已签收的发货行可以退货");}
        return row;
    }

    private void saveLines(CompanyAccess scope,long returnId,FinanceModels.ReturnInput input) {
        int number=0;
        for(var line:input.lines()) {
            var source=source(scope,line.shipmentLineId(),false);
            if(id(source,"customer_id")!=input.customerId()) {throw missing();}
            if(input.businessDate().isBefore(((java.sql.Date)source.get("sign_date")).toLocalDate())) {throw invalid("退货日期不能早于原发货签收日期");}
            // 原单位换算快照不受后来商品资料修改影响。
            BigDecimal rate;
            if(id(source,"unit_id")==line.unitId()) {rate=decimal(source,"unit_rate");}
            else {
                var rates=jdbc.queryForList("SELECT rate_to_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",BigDecimal.class,scope.tenantId(),source.get("sku_id"),line.unitId());
                if(rates.isEmpty()) {throw missing();}rate=rates.getFirst();
            }
            BigDecimal qty=quantity(line.qty()),base;
            try {base=qty.multiply(rate).setScale(6,RoundingMode.UNNECESSARY);}
            catch(ArithmeticException ex) {throw invalid("单位换算后的数量超过六位精度");}
            BigDecimal available=decimal(source,"signed_base_qty").subtract(decimal(source,"returned_base_qty")).subtract(decimal(source,"pending_return_base_qty"));
            if(base.compareTo(available)>0) {throw new ApiException(422,"RETURN_QTY_EXCEEDED","退货数量超过原发货行可退数量");}
            jdbc.update("""
                    INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,shipment_line_id,sku_id,unit_id,unit_rate,qty,base_qty,quality_result,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,Ids.next(),scope.tenantId(),scope.companyId(),returnId,++number,line.shipmentLineId(),source.get("sku_id"),line.unitId(),rate,qty,base,line.qualityResult(),scope.userId(),scope.userId());
        }
    }

    @Transactional
    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,FinanceModels.Version input) {
        return execute(scope,"sales_return."+action,key,List.of(id,input),200,()->{
            var snapshot=header(scope,id,false);
            LocalDate businessDate=input.businessDate()==null?((java.sql.Date)snapshot.get("business_date")).toLocalDate():input.businessDate();
            Long period=null;
            if(action.equals("submit")) {period=support.period(scope,((java.sql.Date)snapshot.get("business_date")).toLocalDate());}
            else if(action.equals("post")) {period=support.period(scope,businessDate);}
            var before=header(scope,id,true);version(before,input.version());
            var lines=jdbc.queryForList("SELECT * FROM sales_return_line WHERE tenant_id=? AND company_id=? AND return_id=? ORDER BY shipment_line_id",scope.tenantId(),scope.companyId(),id);
            lockSources(scope,lines);
            if(action.equals("submit")) {state(before,"draft");occupy(scope,before,lines,true);status(scope,id,"submitted","submitted_at");}
            else if(action.equals("void")) {state(before,"submitted");occupy(scope,before,lines,false);status(scope,id,"voided","voided_at");}
            else if(action.equals("post")) {state(before,"submitted");post(scope,id,before,lines,period,businessDate);}
            else {throw invalid("退货操作无效");}
            if(!action.equals("post")) {
                var orderIds=new TreeSet<Long>();
                for(var line:lines) {orderIds.add(id(source(scope,id(line,"shipment_line_id"),true),"order_id"));}
                for(long order:orderIds) {orders.refreshProgress(scope,order);}
            }
            audit.record(scope,"sales_return."+action,"sales_return",id,null,Map.of("status",get(scope,id).get("status")));return safe(get(scope,id));
        });
    }

    private void occupy(CompanyAccess scope,Map<String,Object> header,List<Map<String,Object>> lines,boolean add) {
        for(var line:lines) {
            var original=source(scope,id(line,"shipment_line_id"),true);
            if(id(original,"customer_id")!=id(header,"customer_id")) {throw missing();}
            BigDecimal qty=decimal(line,"base_qty");
            int updated=jdbc.update("""
                    UPDATE shipment_line SET pending_return_base_qty=pending_return_base_qty+?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=? AND
                    """+(add?"signed_base_qty-returned_base_qty-pending_return_base_qty>=?":"pending_return_base_qty>=?"),
                    add?qty:qty.negate(),scope.tenantId(),scope.companyId(),line.get("shipment_line_id"),qty);
            if(updated!=1) {throw new ApiException(422,"RETURN_QTY_EXCEEDED","原发货行可退数量不足");}
        }
    }

    private void post(CompanyAccess scope,long returnId,Map<String,Object> header,List<Map<String,Object>> lines,long period,LocalDate date) {
        references.requirePartner(scope,id(header,"customer_id"),"customer");
        long warehouse=id(header,"warehouse_id"),customer=id(header,"customer_id");
        var originals=new LinkedHashMap<Long,Map<String,Object>>();
        for(var line:lines) {originals.put(id(line,"shipment_line_id"),source(scope,id(line,"shipment_line_id"),true));}
        inventory.lockKeys(scope,lines.stream().map(line->new Key(warehouse,id(line,"sku_id"))).toList());
        BigDecimal total=BigDecimal.ZERO.setScale(2),tax=total,cost=total;
        var journal=new ArrayList<LedgerService.Line>();var orderIds=new TreeSet<Long>();
        for(var line:lines) {
            var original=originals.get(id(line,"shipment_line_id"));
            if(date.isBefore(((java.sql.Date)original.get("sign_date")).toLocalDate())) {throw invalid("退货过账日期不能早于原发货签收日期");}
            var result=postLine(scope,returnId,warehouse,customer,date,period,line,original,journal);
            total=total.add(result[0]);tax=tax.add(result[1]);cost=cost.add(result[2]);orderIds.add(id(original,"order_id"));
        }
        if(!journal.isEmpty()) {ledger.post(scope,new LedgerService.Posting("sales_return",returnId,0,period,date,journal));}
        jdbc.update("""
                UPDATE sales_return SET status='posted',credit_amount=?,tax_amount=?,untaxed_amount=?,cost_amount=?,business_date=?,
                period_id=?,posted_by=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?
                """,total,tax,total.subtract(tax),cost,date,period,scope.userId(),scope.userId(),returnId);
        for(long order:orderIds) {orders.refreshProgress(scope,order);}
    }

    private void lockSources(CompanyAccess scope,List<Map<String,Object>> lines) {
        var sources=lines.stream().map(line->source(scope,id(line,"shipment_line_id"),false)).toList();
        lockRows(scope,"shipment",sources.stream().map(row->id(row,"shipment_id")).distinct().sorted().toList());
        lockRows(scope,"sales_order",sources.stream().map(row->id(row,"order_id")).distinct().sorted().toList());
        lockRows(scope,"sales_order_line",sources.stream().map(row->id(row,"order_line_id")).distinct().sorted().toList());
        var ordered=sources.stream().sorted(Comparator.comparingLong((Map<String,Object> row)->id(row,"order_line_id")).thenComparingLong(row->id(row,"id"))).map(row->id(row,"id")).toList();
        lockRows(scope,"shipment_line",ordered);
    }

    private void lockRows(CompanyAccess scope,String table,List<Long> ids) {
        for(long id:ids) {jdbc.queryForObject("SELECT id FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);}
    }

    private BigDecimal[] postLine(CompanyAccess scope,long returnId,long warehouse,long customer,LocalDate date,long period,
            Map<String,Object> line,Map<String,Object> original,List<LedgerService.Line> journal) {
        long lineId=id(line,"id"),sourceLine=id(line,"shipment_line_id"),sku=id(line,"sku_id");
        BigDecimal qty=decimal(line,"base_qty"),previous=decimal(original,"returned_base_qty"),signed=decimal(original,"signed_base_qty");
        if(decimal(original,"pending_return_base_qty").compareTo(qty)<0||previous.add(qty).compareTo(signed)>0) {throw new ApiException(422,"RETURN_QTY_EXCEEDED","退货占用与原发货行不一致");}
        var recognition=pricing.recognize(decimal(original,"receivable_amount").toPlainString(),decimal(original,"tax_amount").toPlainString(),signed.toPlainString(),previous.toPlainString(),previous.add(qty).toPlainString());
        BigDecimal credit=new BigDecimal(recognition.totalAmount()),tax=new BigDecimal(recognition.taxAmount()),untaxed=credit.subtract(tax);
        BigDecimal signedCost=decimal(original,"signed_cost_amount");
        BigDecimal cost=signedCost.multiply(previous.add(qty)).divide(signed,2,RoundingMode.HALF_UP)
                .subtract(signedCost.multiply(previous).divide(signed,2,RoundingMode.HALF_UP));
        var posting=new Posting(new Key(warehouse,sku),new Source("sales_return_line",returnId,lineId),date,period,"sales_return_in");
        inventory.receive(scope,posting,qty,cost);
        if(!"passed".equals(line.get("quality_result"))) {
            inventory.freeze(scope,new Posting(posting.key(),posting.source(),date,period,"block"),qty,true);
        }
        jdbc.update("""
                UPDATE sales_return_line SET unit_cost=?,cost_amount=?,credit_amount=?,tax_amount=?,untaxed_amount=?,version=version+1,updated_by=? WHERE id=?
                """,original.get("unit_cost"),cost,credit,tax,untaxed,scope.userId(),lineId);
        jdbc.update("""
                UPDATE shipment_line SET pending_return_base_qty=pending_return_base_qty-?,returned_base_qty=returned_base_qty+?,
                credited_amount=credited_amount+?,credited_tax=credited_tax+?,version=version+1,updated_by=? WHERE id=?
                """,qty,qty,credit,tax,scope.userId(),sourceLine);
        jdbc.update("UPDATE sales_order_line SET returned_base_qty=returned_base_qty+?,version=version+1,updated_by=? WHERE id=?",qty,scope.userId(),original.get("order_line_id"));
        if(credit.signum()>0) {
            long receivable=Ids.next();
            jdbc.update("""
                    INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id,created_by)
                    VALUES(?,?,?,?,'credit','sales_return_line',?,?,?,?,?,?,?,?)
                    """,receivable,scope.tenantId(),scope.companyId(),customer,returnId,lineId,credit,untaxed,tax,date,period,scope.userId());
            settlement.open(scope,new SettlementService.OpenItem("receivable",receivable,customer,"C",credit,date));
            journal.add(new LedgerService.Line("AR","C",credit,customer,null,null,null,lineId,"销售退货信用额"));
        }
        add(journal,"SALES_REVENUE","D",untaxed,null,null,lineId);
        add(journal,"OUTPUT_VAT","D",tax,null,null,lineId);
        add(journal,"INVENTORY","D",cost,warehouse,sku,lineId);
        add(journal,"COGS","C",cost,null,null,lineId);
        return new BigDecimal[]{credit,tax,cost};
    }

    private void add(List<LedgerService.Line> lines,String account,String direction,BigDecimal amount,Long warehouse,Long sku,long line) {
        if(amount.signum()==0) {return;}
        String actual=amount.signum()>0?direction:direction.equals("D")?"C":"D";
        lines.add(new LedgerService.Line(account,actual,amount.abs(),null,null,warehouse,sku,line,"销售退货"));
    }
    private void status(CompanyAccess scope,long id,String status,String timeField) {
        jdbc.update("UPDATE sales_return SET status=?,"+timeField+"=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",status,scope.userId(),id);
    }
    private Map<String,Object> safe(Map<String,Object> result) {
        result.remove("costAmount");
        @SuppressWarnings("unchecked") var lines=(List<Map<String,Object>>)result.get("lines");
        lines.forEach(line->{line.remove("unitCost");line.remove("costAmount");});return result;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String operation,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {
        return commands.execute(scope,operation,key,payload,status,work,(Class)Map.class);
    }
}
