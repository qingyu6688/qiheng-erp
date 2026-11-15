package cn.erp.fulfillment;

import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.fulfillment.ShipmentValues.*;
import static cn.erp.inventory.InventoryModels.*;

@Service
@Transactional
public class ShipmentService {
    private final JdbcTemplate jdbc;
    private final ShipmentQueries queries;
    private final InventoryService inventory;
    private final OrderLineOccupancy occupancy;
    private final CatalogAvailability catalog;
    private final ReferenceAvailability reference;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final DocumentNumbers numbers;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final SalesOrderQueries orders;
    private final ReservationService reservations;

    public ShipmentService(JdbcTemplate jdbc,ShipmentQueries queries,InventoryService inventory,
            OrderLineOccupancy occupancy,CatalogAvailability catalog,ReferenceAvailability reference,
            LedgerService ledger,SettlementService settlement,DocumentNumbers numbers,
            BusinessCommands commands,AuditService audit,SalesOrderQueries orders,ReservationService reservations) {
        this.jdbc=jdbc;this.queries=queries;this.inventory=inventory;this.occupancy=occupancy;this.catalog=catalog;
        this.reference=reference;this.ledger=ledger;this.settlement=settlement;this.numbers=numbers;this.commands=commands;this.audit=audit;this.orders=orders;this.reservations=reservations;
    }

    public Map<String,Object> create(CompanyAccess scope,String key,ShipmentModels.Input input) {
        if(input==null || input.warehouseId()==null || !scope.allowsWarehouse(input.warehouseId())) {throw missing();}
        return command(scope,"create",key,input,201,()->{
            validateInput(scope,input);
            String number=numbers.next(scope,"SH",YearMonth.from(input.businessDate()));
            long id=Ids.next();
            jdbc.update("""
                    INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,
                    business_date,delivery_method,remark,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """,id,scope.tenantId(),scope.companyId(),number,input.customerId(),input.warehouseId(),input.businessDate(),
                    input.deliveryMethod()==null?"delivery":input.deliveryMethod(),input.remark(),scope.userId(),scope.userId());
            saveLines(scope,id,input);
            changed(scope,id,"create",null);
            return safe(scope,id);
        });
    }

    public Map<String,Object> update(CompanyAccess scope,long id,ShipmentModels.Input input) {
        var before=queries.header(scope,id,true);version(input.version(),before);
        if(!"draft".equals(before.get("status"))) {throw state();}
        validateInput(scope,input);
        jdbc.update("""
                UPDATE shipment SET customer_id=?,warehouse_id=?,business_date=?,delivery_method=?,remark=?,
                version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?
                """,input.customerId(),input.warehouseId(),input.businessDate(),input.deliveryMethod()==null?"delivery":input.deliveryMethod(),
                input.remark(),scope.userId(),scope.tenantId(),scope.companyId(),id);
        jdbc.update("DELETE FROM shipment_line WHERE tenant_id=? AND company_id=? AND shipment_id=?",scope.tenantId(),scope.companyId(),id);
        saveLines(scope,id,input);changed(scope,id,"update",before);
        return queries.get(scope,id);
    }

    public Map<String,Object> submit(CompanyAccess scope,long id,String key,long version) {
        queries.header(scope,id,false);
        return command(scope,"submit",key,List.of(id,version),200,()->{
            var snapshot=queries.header(scope,id,false);
            period(scope,date(snapshot,"business_date"));
            var before=queries.header(scope,id,true);version(version,before);
            if(!"draft".equals(before.get("status"))) {throw state();}
            var lines=lockLines(scope,id);
            for(var line:lines) {
                var source=source(scope,id(line,"order_line_id"),true);
                validateSource(scope,before,source);
                catalog.requireActiveSku(scope,id(line,"sku_id"),id(line,"unit_id"));
                occupancy.occupy(scope,id(line,"order_line_id"),decimal(line,"base_qty"));
            }
            jdbc.update("UPDATE shipment SET status='submitted',submitted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",scope.userId(),id);
            changed(scope,id,"submit",before);return safe(scope,id);
        });
    }

    public Map<String,Object> voidSubmitted(CompanyAccess scope,long id,String key,long version) {
        queries.header(scope,id,false);
        return command(scope,"void",key,List.of(id,version),200,()->{
            var before=queries.header(scope,id,true);version(version,before);
            if(!"submitted".equals(before.get("status"))) {throw state();}
            var lines=lockLines(scope,id);
            for(var line:lines) {occupancy.release(scope,id(line,"order_line_id"),decimal(line,"base_qty"));}
            jdbc.update("UPDATE shipment SET status='voided',voided_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",scope.userId(),id);
            changed(scope,id,"void",before);return safe(scope,id);
        });
    }

    public Map<String,Object> post(CompanyAccess scope,long id,String key,long expectedVersion) {
        queries.header(scope,id,false);
        return command(scope,"post",key,List.of(id,expectedVersion),200,()->{
            var snapshot=queries.header(scope,id,false);
            long period=period(scope,date(snapshot,"business_date"));
            var before=queries.header(scope,id,true);version(expectedVersion,before);
            if(!"submitted".equals(before.get("status"))) {throw state();}
            var lines=lockLines(scope,id);
            inventory.lockKeys(scope,lines.stream().map(line->new Key(id(before,"warehouse_id"),id(line,"sku_id"))).toList());
            var entries=new ArrayList<LedgerService.Line>();BigDecimal total=ZERO;
            for(var line:lines) {
                var source=source(scope,id(line,"order_line_id"),true);validateSource(scope,before,source,true);
                Long reservation=jdbc.queryForList("""
                        SELECT id FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND order_line_id=? AND status='active' ORDER BY id
                        """,Long.class,scope.tenantId(),scope.companyId(),line.get("order_line_id")).stream().findFirst().orElse(null);
                var posting=new Posting(new Key(id(before,"warehouse_id"),id(line,"sku_id")),new Source("shipment_line",id,id(line,"id")),
                        date(before,"business_date"),period,"sales_out");
                var cost=inventory.issue(scope,posting,decimal(line,"base_qty"),reservation);
                occupancy.complete(scope,id(line,"order_line_id"),decimal(line,"base_qty"));
                jdbc.update("UPDATE shipment_line SET unit_cost=?,cost_amount=?,version=version+1,updated_by=? WHERE id=?",
                        cost.unitCost(),cost.movementCost(),scope.userId(),line.get("id"));
                total=total.add(cost.movementCost());
                add(entries,"GOODS_SHIPPED","D",cost.movementCost(),null,id(before,"warehouse_id"),id(line,"sku_id"),id(line,"id"));
                add(entries,"INVENTORY","C",cost.movementCost(),null,id(before,"warehouse_id"),id(line,"sku_id"),id(line,"id"));
                add(entries,"INVENTORY","D",cost.adjustment(),null,id(before,"warehouse_id"),id(line,"sku_id"),id(line,"id"));
                add(entries,"COST_VARIANCE","C",cost.adjustment(),null,null,null,id(line,"id"));
            }
            postJournal(scope,"shipment_post",id,0,period,date(before,"business_date"),entries);
            jdbc.update("UPDATE shipment SET status='posted',cost_amount=?,period_id=?,posted_by=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",
                    total,period,scope.userId(),scope.userId(),id);
            refresh(scope,lines);
            if("pickup".equals(before.get("delivery_method"))) {
                var signed=lines.stream().map(line->new ShipmentModels.SignedLine(id(line,"id"),decimal(line,"base_qty").toPlainString())).toList();
                signInternal(scope,id,new ShipmentModels.Signed(expectedVersion+1,date(before,"business_date"),signed,List.of()));
            }
            changed(scope,id,"post",before);return safe(scope,id);
        });
    }

    public Map<String,Object> sign(CompanyAccess scope,long id,String key,ShipmentModels.Signed input) {
        queries.header(scope,id,false);
        return command(scope,"sign",key,List.of(id,input),200,()->{
            var before=queries.header(scope,id,false);
            signInternal(scope,id,input);changed(scope,id,"sign",before);return safe(scope,id);
        });
    }

    private void signInternal(CompanyAccess scope,long id,ShipmentModels.Signed input) {
        var snapshot=queries.header(scope,id,false);
        if(input.signDate()==null || input.signDate().isBefore(date(snapshot,"business_date")) || input.signDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
            throw new ApiException(422,"SIGN_DATE_INVALID","签收日期不能早于发货日或晚于当天");
        }
        long period=period(scope,input.signDate());
        var header=queries.header(scope,id,true);version(input.version(),header);
        if(!"posted".equals(header.get("status"))) {throw state();}
        validateAttachments(scope,id,input.attachmentIds());
        var lines=lockLines(scope,id);
        var signed=new HashMap<Long,BigDecimal>();
        if(input.lines()==null) {throw invalid("请登记所有发货行的签收数量");}
        for(var line:input.lines()) {
            if(line==null || line.lineId()==null || signed.put(line.lineId(),quantity(line.signedQty(),true))!=null) {throw invalid("签收行不能为空或重复");}
        }
        if(signed.size()!=lines.size() || !signed.keySet().equals(lines.stream().map(row->id(row,"id")).collect(java.util.stream.Collectors.toSet()))) {
            throw invalid("签收必须包含本发货单的所有行");
        }
        BigDecimal total=ZERO,tax=ZERO,cost=ZERO;boolean hasDifference=false;
        var entries=new ArrayList<LedgerService.Line>();
        var signedOrders=new TreeSet<Long>();
        for(var line:lines) {
            BigDecimal qty=signed.get(id(line,"id")),sent=decimal(line,"base_qty");
            if(qty.compareTo(sent)>0) {throw new ApiException(422,"SIGN_QTY_EXCEEDED","签收数量不能超过发货数量");}
            var source=source(scope,id(line,"order_line_id"),true);
            BigDecimal cumulative=decimal(source,"signed_base_qty").add(qty);
            BigDecimal amount=decimal(source,"receivable_net").multiply(cumulative).divide(decimal(source,"base_qty"),2,RoundingMode.HALF_UP).subtract(decimal(source,"recognized_amount"));
            BigDecimal taxAmount=decimal(source,"tax_amount").multiply(cumulative).divide(decimal(source,"base_qty"),2,RoundingMode.HALF_UP).subtract(decimal(source,"recognized_tax"));
            if(amount.signum()<0 || taxAmount.signum()<0) {throw new ApiException(409,"STATE_CONFLICT","订单金额快照与累计确认不一致，请先核对");}
            BigDecimal signedCost=qty.compareTo(sent)==0?decimal(line,"cost_amount"):decimal(line,"unit_cost").multiply(qty).setScale(2,RoundingMode.HALF_UP).min(decimal(line,"cost_amount"));
            BigDecimal diff=sent.subtract(qty),diffCost=decimal(line,"cost_amount").subtract(signedCost);
            hasDifference|=diff.signum()>0;
            jdbc.update("""
                    UPDATE shipment_line SET signed_base_qty=?,diff_base_qty=?,diff_disposition=?,receivable_amount=?,tax_amount=?,untaxed_amount=?,
                    signed_cost_amount=?,diff_cost_amount=?,version=version+1,updated_by=? WHERE id=?
                    """,qty,diff,diff.signum()==0?"none":"pending",amount,taxAmount,amount.subtract(taxAmount),signedCost,diffCost,scope.userId(),line.get("id"));
            jdbc.update("""
                    UPDATE sales_order_line SET signed_base_qty=signed_base_qty+?,shipped_base_qty=shipped_base_qty-?,
                    recognized_amount=recognized_amount+?,recognized_tax=recognized_tax+?,
                    version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?
                    """,qty,diff,amount,taxAmount,scope.userId(),scope.tenantId(),scope.companyId(),line.get("order_line_id"));
            reservations.refreshLineState(scope,id(line,"order_line_id"));
            if(qty.signum()>0) {signedOrders.add(id(source,"order_id"));}
            createReceivable(scope,id,id(line,"id"),"shipment_line",id(header,"customer_id"),id(source,"order_id"),amount,taxAmount,input.signDate(),period);
            add(entries,"AR","D",amount,id(header,"customer_id"),null,null,id(line,"id"));
            add(entries,"SALES_REVENUE","C",amount.subtract(taxAmount),null,null,null,id(line,"id"));
            add(entries,"OUTPUT_VAT","C",taxAmount,null,null,null,id(line,"id"));
            add(entries,"COGS","D",signedCost,null,null,null,id(line,"id"));
            add(entries,"GOODS_SHIPPED","C",signedCost,null,id(header,"warehouse_id"),id(line,"sku_id"),id(line,"id"));
            total=total.add(amount);tax=tax.add(taxAmount);cost=cost.add(signedCost);
        }
        for(long order:signedOrders) {
            for(var fee:jdbc.queryForList("SELECT * FROM sales_order_fee WHERE tenant_id=? AND company_id=? AND order_id=? AND bearer='buyer' AND recognized_shipment_id IS NULL ORDER BY id FOR UPDATE",
                    scope.tenantId(),scope.companyId(),order)) {
                BigDecimal amount=decimal(fee,"total_amount"),feeTax=decimal(fee,"tax_amount");
                createReceivable(scope,id,id(fee,"id"),"sales_order_fee",id(header,"customer_id"),order,amount,feeTax,input.signDate(),period);
                jdbc.update("UPDATE sales_order_fee SET recognized_shipment_id=?,version=version+1,updated_by=? WHERE id=?",id,scope.userId(),fee.get("id"));
                total=total.add(amount);tax=tax.add(feeTax);
                add(entries,"AR","D",amount,id(header,"customer_id"),null,null,id(fee,"id"));
                add(entries,"FEE_INCOME","C",amount.subtract(feeTax),null,null,null,id(fee,"id"));
                add(entries,"OUTPUT_VAT","C",feeTax,null,null,null,id(fee,"id"));
            }
        }
        postJournal(scope,"shipment_sign",id,0,period,input.signDate(),entries);
        jdbc.update("""
                UPDATE shipment SET status=?,sign_date=?,sign_period_id=?,signed_by=?,signed_at=UTC_TIMESTAMP(3),
                receivable_amount=?,tax_amount=?,untaxed_amount=?,signed_cost_amount=?,version=version+1,updated_by=? WHERE id=?
                """,hasDifference?"signed_with_diff":"signed",input.signDate(),period,scope.userId(),total,tax,total.subtract(tax),cost,scope.userId(),id);
        refresh(scope,lines);
    }

    public Map<String,Object> resolve(CompanyAccess scope,long id,String key,ShipmentModels.Resolution input) {
        queries.header(scope,id,false);
        return command(scope,"resolve_diff",key,List.of(id,input),200,()->{
            long period=period(scope,input.businessDate());
            var header=queries.header(scope,id,true);version(input.version(),header);
            if(!"signed_with_diff".equals(header.get("status"))) {throw state();}
            var lines=lockLines(scope,id);
            if(input.businessDate().isBefore(date(header,"sign_date"))) {throw invalid("差异处理日期不能早于签收日期");}
            inventory.lockKeys(scope,lines.stream().map(line->new Key(id(header,"warehouse_id"),id(line,"sku_id"))).toList());
            var entries=new ArrayList<LedgerService.Line>();var used=new HashSet<Long>();
            if(input.lines()==null || input.lines().isEmpty()) {throw invalid("请选择需要处理的差异行");}
            for(var resolve:input.lines()) {
                if(resolve==null || resolve.lineId()==null || !used.add(resolve.lineId()) || resolve.disposition()==null || !Set.of("returned","lost").contains(resolve.disposition()) ||
                        resolve.reason()==null || resolve.reason().isBlank() || resolve.reason().length()>200) {throw invalid("差异行、处理方式或原因无效");}
                var line=lines.stream().filter(row->id(row,"id")==resolve.lineId()).findFirst().orElseThrow(ShipmentValues::missing);
                if(!"pending".equals(line.get("diff_disposition"))) {throw state();}
                BigDecimal diff=decimal(line,"diff_base_qty"),diffCost=decimal(line,"diff_cost_amount");
                if(resolve.disposition().equals("returned")) {
                    inventory.receive(scope,new Posting(new Key(id(header,"warehouse_id"),id(line,"sku_id")),new Source("shipment_diff",id,id(line,"id")),
                            input.businessDate(),period,"sales_reject_in"),diff,diffCost);
                    add(entries,"INVENTORY","D",diffCost,null,id(header,"warehouse_id"),id(line,"sku_id"),id(line,"id"));
                } else {add(entries,"INVENTORY_ADJUSTMENT","D",diffCost,null,id(header,"warehouse_id"),id(line,"sku_id"),id(line,"id"));}
                add(entries,"GOODS_SHIPPED","C",diffCost,null,id(header,"warehouse_id"),id(line,"sku_id"),id(line,"id"));
                jdbc.update("UPDATE shipment_line SET diff_disposition=?,version=version+1,updated_by=? WHERE id=?",resolve.disposition(),scope.userId(),line.get("id"));
            }
            postJournal(scope,"shipment_diff",id,Math.toIntExact(input.version()),period,input.businessDate(),entries);
            long remaining=jdbc.queryForObject("SELECT COUNT(*) FROM shipment_line WHERE tenant_id=? AND company_id=? AND shipment_id=? AND diff_disposition='pending'",Long.class,scope.tenantId(),scope.companyId(),id);
            jdbc.update("UPDATE shipment SET status=?,version=version+1,updated_by=? WHERE id=?",remaining==0?"signed":"signed_with_diff",scope.userId(),id);
            var after=new LinkedHashMap<>(safe(scope,id));
            after.put("resolutions",input.lines());after.put("businessDate",input.businessDate());
            audit.record(scope,"shipment.resolve_diff","shipment",id,null,after);return safe(scope,id);
        });
    }

    private void createReceivable(CompanyAccess scope,long shipment,long line,String type,long customer,long order,
            BigDecimal amount,BigDecimal tax,LocalDate date,long period) {
        if(amount.signum()==0) {return;}
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,
                amount,untaxed_amount,tax_amount,business_date,period_id,created_by) VALUES(?,?,?,?,'charge',?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),customer,type,shipment,line,amount,amount.subtract(tax),tax,date,period,scope.userId());
        long item=settlement.open(scope,new SettlementService.OpenItem("receivable",id,customer,"D",amount,date));
        settlement.autoAllocateOrder(scope,order,item);
    }

    private void saveLines(CompanyAccess scope,long shipment,ShipmentModels.Input input) {
        if(input.lines().stream().anyMatch(line->line==null || line.orderLineId()==null || line.unitId()==null)) {throw invalid("请填写有效的订单行和单位");}
        lockOrders(scope,input.lines().stream().sorted(Comparator.comparing(ShipmentModels.InputLine::orderLineId))
                .map(line->Map.<String,Object>of("order_line_id",line.orderLineId())).toList());
        var seen=new HashSet<Long>();int number=0;
        for(var line:input.lines()) {
            if(!seen.add(line.orderLineId())) {throw invalid("同一订单行不能在一张发货单里重复");}
            var source=source(scope,line.orderLineId(),true);
            var header=Map.<String,Object>of("customer_id",input.customerId(),"warehouse_id",input.warehouseId());
            validateSource(scope,header,source);
            catalog.requireActiveSku(scope,id(source,"sku_id"),line.unitId());
            BigDecimal rate;
            if(line.unitId()==id(source,"unit_id")) {rate=decimal(source,"unit_rate");}
            else {
                var rates=jdbc.queryForList("SELECT rate_to_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",BigDecimal.class,scope.tenantId(),source.get("sku_id"),line.unitId());
                if(rates.isEmpty()) {throw invalid("所选单位没有商品换算关系");}rate=rates.getFirst();
            }
            BigDecimal qty=quantity(line.qty(),false),base;
            try {base=qty.multiply(rate).setScale(6,RoundingMode.UNNECESSARY);}
            catch(ArithmeticException error) {throw invalid("换算后的基础数量超过6位小数");}
            quantity(base.toPlainString(),false);
            jdbc.update("""
                    INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,Ids.next(),scope.tenantId(),scope.companyId(),shipment,++number,line.orderLineId(),source.get("sku_id"),line.unitId(),rate,qty,base,scope.userId(),scope.userId());
        }
    }

    private void validateInput(CompanyAccess scope,ShipmentModels.Input input) {
        if(input==null || input.customerId()==null || input.warehouseId()==null || input.lines()==null || input.lines().isEmpty() || input.lines().size()>500) {throw invalid("请填写客户、仓库及发货明细");}
        date(input.businessDate());reference.requirePartner(scope,input.customerId(),"customer");
        if(!scope.allowsWarehouse(input.warehouseId()) || jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",
                Long.class,scope.tenantId(),scope.companyId(),input.warehouseId())==0) {throw missing();}
        if(input.deliveryMethod()!=null && !Set.of("delivery","pickup").contains(input.deliveryMethod())) {throw invalid("交货方式无效");}
    }

    private Map<String,Object> source(CompanyAccess scope,long line,boolean lock) {
        var rows=jdbc.queryForList("""
                SELECT l.*,h.customer_id,h.status AS order_status FROM sales_order_line l
                JOIN sales_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.id=? AND l.is_deleted=0 AND h.is_deleted=0
                """+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),line);
        if(rows.isEmpty()) {throw missing();}return rows.getFirst();
    }

    private void validateSource(CompanyAccess scope,Map<String,Object> header,Map<String,Object> source) {
        validateSource(scope,header,source,false);
    }

    private void validateSource(CompanyAccess scope,Map<String,Object> header,Map<String,Object> source,boolean alreadySubmitted) {
        if(!Objects.equals(((Number)header.get("customer_id")).longValue(),id(source,"customer_id")) ||
                source.get("warehouse_id")==null || id(header,"warehouse_id")!=id(source,"warehouse_id") ||
                !scope.allowsWarehouse(id(source,"warehouse_id"))) {throw invalid("发货行须属于当前客户和仓库");}
        if("change_pending".equals(source.get("order_status"))) {
            if(alreadySubmitted) {return;}
            throw new ApiException(422,"CHANGE_PENDING","订单有待审核变更，暂不能发货");
        }
        if(!Set.of("approved","closed").contains(source.get("order_status"))) {throw state();}
    }

    private void lockOrders(CompanyAccess scope,List<Map<String,Object>> lines) {
        var ids=new TreeSet<Long>();
        for(var line:lines) {ids.add(id(source(scope,id(line,"order_line_id"),false),"order_id"));}
        for(long order:ids) {jdbc.queryForObject("SELECT id FROM sales_order WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),order);}
        for(var line:lines) {source(scope,id(line,"order_line_id"),true);}
    }

    private List<Map<String,Object>> lockLines(CompanyAccess scope,long shipment) {
        // 先锁订单再锁发货行，退货和差异返库沿用同一来源锁序。
        lockOrders(scope,queries.lines(scope,shipment,false));
        return queries.lines(scope,shipment,true);
    }

    private void refresh(CompanyAccess scope,List<Map<String,Object>> lines) {
        var ids=new TreeSet<Long>();
        for(var line:lines) {ids.add(id(source(scope,id(line,"order_line_id"),false),"order_id"));}
        for(long order:ids) {orders.refreshProgress(scope,order);}
    }

    private long period(CompanyAccess scope,LocalDate day) {
        date(day);
        var ids=jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",Long.class,scope.tenantId(),scope.companyId(),day,day);
        if(ids.size()!=1) {throw new ApiException(422,"PERIOD_CLOSED","业务日期没有开放的会计期间");}
        ledger.lockOpenPeriod(scope,ids.getFirst(),day);return ids.getFirst();
    }

    private void validateAttachments(CompanyAccess scope,long shipment,List<Long> ids) {
        if(ids==null) {return;}
        if(ids.size()>20 || new HashSet<>(ids).size()!=ids.size()) {throw invalid("附件数量超限或重复");}
        for(Long id:ids) {
            if(id==null || jdbc.queryForObject("""
                    SELECT COUNT(*) FROM attachment WHERE tenant_id=? AND company_id=? AND id=? AND owner_type='shipment' AND owner_id=? AND status='active'
                    """,Long.class,scope.tenantId(),scope.companyId(),id,shipment)==0) {throw missing();}
        }
    }

    private void changed(CompanyAccess scope,long id,String action,Object before) {
        Map<String,Object> prior=null;
        if(before instanceof Map<?,?> row) {
            prior=new LinkedHashMap<>();
            for(String key:List.of("status","version","receivable_amount","tax_amount")) {prior.put(key,row.get(key));}
        }
        audit.record(scope,"shipment."+action,"shipment",id,prior,safe(scope,id));
    }

    private Map<String,Object> safe(CompanyAccess scope,long id) {
        var permissions=new HashSet<>(scope.permissions());permissions.remove("cost:view");
        return queries.get(new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),scope.allWarehouses(),scope.warehouseIds(),permissions),id);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> command(CompanyAccess scope,String action,String key,Object payload,int status,Supplier<Map<String,Object>> work) {
        return commands.execute(scope,"shipment."+action,key,payload,status,work,(Class)Map.class);
    }

    private void add(List<LedgerService.Line> entries,String account,String direction,BigDecimal amount,
            Long customer,Long warehouse,Long sku,Long sourceLine) {
        if(amount.signum()==0) {return;}
        entries.add(new LedgerService.Line(account,amount.signum()>0?direction:direction.equals("D")?"C":"D",amount.abs(),customer,null,warehouse,sku,sourceLine,null));
    }

    private void postJournal(CompanyAccess scope,String source,long id,int revision,long period,LocalDate date,List<LedgerService.Line> entries) {
        // 每个商品或费用对应一段平衡分录，长单据按平衡边界拆凭证，共用外层事务。
        final int maxLines=1000;
        int start=0;
        while(start<entries.size()) {
            BigDecimal balance=BigDecimal.ZERO;int end=-1;
            for(int index=start;index<Math.min(start+maxLines,entries.size());index++) {
                var line=entries.get(index);
                balance=balance.add(line.direction().equals("D")?line.amount():line.amount().negate());
                if(balance.signum()==0) {end=index+1;}
            }
            if(end<=start) {throw new IllegalStateException("发货分录没有可拆分的平衡边界");}
            ledger.post(scope,new LedgerService.Posting(source,id,revision++,period,date,entries.subList(start,end)));
            start=end;
        }
    }
}
