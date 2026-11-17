package cn.erp.receiving;

import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.pricing.PricingEngine;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.receiving.ReceivingSupport.*;
import static cn.erp.receiving.ReceivingQueries.*;

@Service
@Transactional
public class ReceivingService {
    private final JdbcTemplate jdbc;
    private final ReceivingQueries queries;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final AuditService audit;
    private final CatalogAvailability catalog;
    private final ReferenceAvailability references;
    private final PricingEngine pricing;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final PurchaseOrderQueries orders;
    private final ReceiptQualityPort quality;
    public ReceivingService(JdbcTemplate jdbc,ReceivingQueries queries,BusinessCommands commands,DocumentNumbers numbers,AuditService audit,
            CatalogAvailability catalog,ReferenceAvailability references,PricingEngine pricing,InventoryService inventory,
            LedgerService ledger,SettlementService settlement,PurchaseOrderQueries orders,ReceiptQualityPort quality) {
        this.jdbc=jdbc;this.queries=queries;this.commands=commands;this.numbers=numbers;this.audit=audit;this.catalog=catalog;
        this.references=references;this.pricing=pricing;this.inventory=inventory;this.ledger=ledger;this.settlement=settlement;this.orders=orders;this.quality=quality;
    }
    private record Line(long sourceId,long unitId,String qty,StockSelection selection) {}
    public Map<String,Object> createReceipt(CompanyAccess scope,String key,ReceivingModels.ReceiptInput input) {
        return execute(scope,false,"create",key,input,201,()->create(scope,false,input.supplierId(),input.warehouseId(),input.businessDate(),input.remark(),input.lines().stream().map(l->new Line(l.orderLineId(),l.unitId(),l.qty(),l.stockSelection())).toList(),input.buyerUserId()));
    }
    public Map<String,Object> createReturn(CompanyAccess scope,String key,ReceivingModels.ReturnInput input) {
        return execute(scope,true,"create",key,input,201,()->create(scope,true,input.supplierId(),input.warehouseId(),input.businessDate(),input.reason(),input.lines().stream().map(l->new Line(l.receiptLineId(),l.unitId(),l.qty(),l.stockSelection())).toList(),input.buyerUserId()));
    }
    public Map<String,Object> updateReceipt(CompanyAccess scope,long id,ReceivingModels.ReceiptInput input) {
        return update(scope,false,id,input.version(),input.supplierId(),input.warehouseId(),input.businessDate(),input.remark(),input.lines().stream().map(l->new Line(l.orderLineId(),l.unitId(),l.qty(),l.stockSelection())).toList(),input.buyerUserId());
    }
    public Map<String,Object> updateReturn(CompanyAccess scope,long id,ReceivingModels.ReturnInput input) {
        return update(scope,true,id,input.version(),input.supplierId(),input.warehouseId(),input.businessDate(),input.reason(),input.lines().stream().map(l->new Line(l.receiptLineId(),l.unitId(),l.qty(),l.stockSelection())).toList(),input.buyerUserId());
    }
    private Map<String,Object> create(CompanyAccess scope,boolean returning,long supplier,long warehouse,LocalDate date,String note,List<Line> lines,Long buyerUserId) {
        validateHeader(scope,returning,supplier,warehouse,date,note);
        long id=Ids.next();String no=numbers.next(scope,returning?"PR_RETURN":"GR",date);
        jdbc.update("INSERT INTO "+table(returning)+"(id,tenant_id,company_id,"+(returning?"return_no":"receipt_no")+",supplier_id,warehouse_id,business_date,"+(returning?"reason":"remark")+",created_by,updated_by,owner_user_id,owner_department_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,scope.tenantId(),scope.companyId(),no,supplier,warehouse,date,note,scope.userId(),scope.userId(),scope.userId(),cn.erp.shared.OrgOwnership.department(scope));
        writeLines(scope,returning,id,supplier,warehouse,date,lines);
        cn.erp.shared.BusinessPeople.assign(jdbc,scope,table(returning),id,buyerUserId);
        var result=safe(scope,returning,id);audit.record(scope,table(returning)+".create",table(returning),id,null,result);return result;
    }
    private Map<String,Object> update(CompanyAccess scope,boolean returning,long id,Long expected,long supplier,long warehouse,LocalDate date,String note,List<Line> lines,Long buyerUserId) {
        var before=queries.header(scope,returning,id,true);version(expected,before);state(before,"draft");validateHeader(scope,returning,supplier,warehouse,date,note);
        jdbc.update("UPDATE "+table(returning)+" SET supplier_id=?,warehouse_id=?,business_date=?,"+(returning?"reason":"remark")+"=?,version=version+1,updated_by=? WHERE id=?",supplier,warehouse,date,note,scope.userId(),id);
        jdbc.update("DELETE FROM "+table(returning)+"_line WHERE tenant_id=? AND company_id=? AND "+parent(returning)+"=?",scope.tenantId(),scope.companyId(),id);
        writeLines(scope,returning,id,supplier,warehouse,date,lines);cn.erp.shared.BusinessPeople.assign(jdbc,scope,table(returning),id,buyerUserId);audit.record(scope,table(returning)+".update",table(returning),id,null,Map.of("version",expected+1));return queries.get(scope,returning,id);
    }
    private void validateHeader(CompanyAccess scope,boolean returning,long supplier,long warehouse,LocalDate date,String note) {
        date(date);references.requirePartner(scope,supplier,"supplier");
        if(returning&&(note==null||note.isBlank()||note.length()>200)) {throw invalid("请填写200字以内退货原因");}
        if(!scope.allowsWarehouse(warehouse)) {throw missing();}
        if(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId(),warehouse)!=1) {throw new ApiException(422,"MASTER_DATA_DISABLED","仓库不存在或已停用");}
    }
    private void writeLines(CompanyAccess scope,boolean returning,long header,long supplier,long warehouse,LocalDate date,List<Line> inputs) {
        if(inputs==null||inputs.isEmpty()||inputs.size()>500) {throw invalid("明细须为1到500行");}
        Set<Long> used=new HashSet<>();int no=0;
        for(var input:inputs) {
            if(!used.add(input.sourceId())) {throw invalid("同一来源行不能重复");}
            var original=originalSource(scope,returning,input.sourceId(),false);validateSource(scope,returning,supplier,warehouse,date,original,false);
            catalog.requireActiveSku(scope,id(original,"sku_id"),input.unitId());
            BigDecimal rate=id(original,"unit_id")==input.unitId()?decimal(original,"unit_rate"):jdbc.queryForObject("SELECT rate_to_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",BigDecimal.class,scope.tenantId(),original.get("sku_id"),input.unitId());
            BigDecimal qty=quantity(input.qty()),base=new BigDecimal(pricing.convertQuantity(qty.toPlainString(),rate.toPlainString()));
            jdbc.update("INSERT INTO "+table(returning)+"_line(id,tenant_id,company_id,"+parent(returning)+",line_no,"+source(returning)+",sku_id,unit_id,unit_rate,qty,base_qty,stock_selection,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Ids.next(),scope.tenantId(),scope.companyId(),header,++no,input.sourceId(),original.get("sku_id"),input.unitId(),rate,qty,base,inventory.encodeSelection(input.selection()),scope.userId(),scope.userId());
        }
    }
    public Map<String,Object> command(CompanyAccess scope,boolean returning,long id,String action,String key,Long expectedVersion) {
        queries.header(scope,returning,id,false);
        return execute(scope,returning,action,key,List.of(id,expectedVersion==null?-1:expectedVersion),200,()->{
            var snapshot=queries.header(scope,returning,id,false);
            Long period=Set.of("submit","post").contains(action)?period(scope,date(snapshot,"business_date")):null;
            var before=queries.header(scope,returning,id,true);version(expectedVersion,before);
            state(before,action.equals("submit")?"draft":"submitted");
            var lines=lockSources(scope,returning,id);
            if(action.equals("submit")) {submit(scope,returning,before,lines);}
            else if(action.equals("void")) {release(scope,returning,before,lines);}
            else if(action.equals("post")) {post(scope,returning,before,lines,period);}
            else {throw invalid("单据操作无效");}
            var result=safe(scope,returning,id);audit.record(scope,table(returning)+"."+action,table(returning),id,Map.of("status",before.get("status"),"version",before.get("version")),result);return result;
        });
    }
    private List<Map<String,Object>> lockSources(CompanyAccess scope,boolean returning,long headerId) {
        var lines=queries.lines(scope,returning,headerId,false);var receiptIds=new TreeSet<Long>();var orderIds=new TreeSet<Long>();
        for(var line:lines) {
            var original=originalSource(scope,returning,id(line,source(returning)),false);
            orderIds.add(id(original,"order_id"));if(returning) {receiptIds.add(id(original,"receipt_id"));}
        }
        for(long id:receiptIds) {if(jdbc.queryForList("SELECT id FROM goods_receipt WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"goods_receipt")+" AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id).isEmpty()) throw missing();}
        for(long id:orderIds) {if(jdbc.queryForList("SELECT id FROM purchase_order WHERE tenant_id=? AND company_id=?"+cn.erp.shared.OrgOwnership.filter(scope,"purchase_order")+" AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id).isEmpty()) throw missing();}
        var orderLines=new TreeSet<Long>();
        for(var line:lines) {var original=originalSource(scope,returning,id(line,source(returning)),false);orderLines.add(returning?id(original,"order_line_id"):id(original,"id"));}
        for(long id:orderLines) {jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),id);}
        for(var line:lines) {originalSource(scope,returning,id(line,source(returning)),true);}
        return queries.lines(scope,returning,headerId,true);
    }
    private Map<String,Object> originalSource(CompanyAccess scope,boolean returning,long id,boolean lock) {
        String sql=returning?"""
            SELECT l.*,h.supplier_id,h.warehouse_id,h.business_date source_date,h.status source_status,h.cost_tax_inclusive,
              o.order_id FROM goods_receipt_line l JOIN goods_receipt h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.receipt_id
              JOIN purchase_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id
            WHERE l.tenant_id=? AND l.company_id=? AND l.id=?
            """:"""
            SELECT l.*,h.supplier_id,h.business_date source_date,h.status source_status FROM purchase_order_line l
            JOIN purchase_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
            WHERE l.tenant_id=? AND l.company_id=? AND l.id=? AND l.is_deleted=0 AND h.is_deleted=0
            """;
        var rows=jdbc.queryForList(sql+cn.erp.shared.OrgOwnership.filter(scope,"h")+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}
        var row=rows.getFirst();if(row.get("warehouse_id")!=null&&!scope.allowsWarehouse(id(row,"warehouse_id"))) {throw missing();}return row;
    }
    private void validateSource(CompanyAccess scope,boolean returning,long supplier,long warehouse,LocalDate day,Map<String,Object> original,boolean posting) {
        if(id(original,"supplier_id")!=supplier) {throw invalid("来源必须属于当前供应商");}
        if(!returning&&original.get("warehouse_id")!=null&&id(original,"warehouse_id")!=warehouse) {throw invalid("收货仓库必须与采购订单行一致");}
        if(returning) {if(!"posted".equals(original.get("source_status"))) {throw new ApiException(409,"STATE_CONFLICT","原收货单尚未过账或已经冲销");}}
        else {
            if("change_pending".equals(original.get("source_status"))&&!posting) {throw new ApiException(422,"CHANGE_PENDING","采购订单有待审核变更，暂不能新建或提交收货");}
            if(!Set.of("approved","closed","change_pending").contains(original.get("source_status"))) {throw new ApiException(409,"STATE_CONFLICT","来源采购订单状态不能收货");}
        }
        if(day.isBefore(date(original,"source_date"))) {throw invalid(returning?"退货日期不能早于原收货日期":"收货日期不能早于订单日期");}
    }
    private void submit(CompanyAccess scope,boolean returning,Map<String,Object> header,List<Map<String,Object>> lines) {
        validateHeader(scope,returning,id(header,"supplier_id"),id(header,"warehouse_id"),date(header,"business_date"),(String)header.get(returning?"reason":"remark"));
        for(var line:lines) {
            var original=originalSource(scope,returning,id(line,source(returning)),true);validateSource(scope,returning,id(header,"supplier_id"),id(header,"warehouse_id"),date(header,"business_date"),original,false);
            if(!returning) {
                validateReceiptSnapshot(line,original);
                long sku=id(line,"sku_id");
                inventory.validateInbound(scope,new Key(id(header,"warehouse_id"),sku),decimal(line,"base_qty"),
                        inventory.decodeSelection(line.get("stock_selection")),quality.requires(scope,sku,id(header,"supplier_id"))?"quality_hold":null);
            }
            String sql=returning?"UPDATE goods_receipt_line SET pending_return_base_qty=pending_return_base_qty+?,version=version+1,updated_by=? WHERE id=? AND base_qty-returned_base_qty-pending_return_base_qty>=?":"UPDATE purchase_order_line SET pending_receive_base_qty=pending_receive_base_qty+?,version=version+1,updated_by=? WHERE id=? AND receive_limit_base_qty-closed_base_qty-received_base_qty-pending_receive_base_qty>=?";
            if(jdbc.update(sql,line.get("base_qty"),scope.userId(),original.get("id"),line.get("base_qty"))!=1) {throw new ApiException(422,returning?"RETURN_QTY_EXCEEDED":"RECEIVE_QTY_EXCEEDED",returning?"退货数量超过原收货可退数量":"收货数量超过审批时的可收上限");}
        }
        status(scope,returning,id(header,"id"),"submitted","submitted_at");refresh(scope,returning,lines);
    }
    private void validateReceiptSnapshot(Map<String,Object> line,Map<String,Object> original) {
        boolean changed=id(line,"sku_id")!=id(original,"sku_id") ||
            id(line,"unit_id")==id(original,"unit_id")&&decimal(line,"unit_rate").compareTo(decimal(original,"unit_rate"))!=0;
        try {
            var base=new BigDecimal(pricing.convertQuantity(decimal(line,"qty").toPlainString(),decimal(line,"unit_rate").toPlainString()));
            changed|=base.compareTo(decimal(line,"base_qty"))!=0;
        } catch(ApiException error) {changed=true;}
        if(changed) {throw new ApiException(409,"SOURCE_CHANGED","采购来源或数量换算已变更，请编辑或重新创建收货草稿");}
    }
    private void release(CompanyAccess scope,boolean returning,Map<String,Object> header,List<Map<String,Object>> lines) {
        for(var line:lines) {
            String sql=returning?"UPDATE goods_receipt_line SET pending_return_base_qty=pending_return_base_qty-?,version=version+1,updated_by=? WHERE id=? AND pending_return_base_qty>=?":"UPDATE purchase_order_line SET pending_receive_base_qty=pending_receive_base_qty-?,version=version+1,updated_by=? WHERE id=? AND pending_receive_base_qty>=?";
            if(jdbc.update(sql,line.get("base_qty"),scope.userId(),line.get(source(returning)),line.get("base_qty"))!=1) {throw new ApiException(409,"STATE_CONFLICT","来源占用数量不一致，请先核对");}
        }
        status(scope,returning,id(header,"id"),"voided","voided_at");refresh(scope,returning,lines);
    }
    private void post(CompanyAccess scope,boolean returning,Map<String,Object> header,List<Map<String,Object>> lines,long period) {
        long warehouse=id(header,"warehouse_id"),id=id(header,"id"),supplier=id(header,"supplier_id");LocalDate day=date(header,"business_date");
        var holds=new HashMap<Long,ReceiptQualityPort.Hold>();
        if(returning)for(var line:lines){
            long lineId=id(line,"id");
            var hold=quality.beforePurchaseReturn(scope,id,lineId,id(line,"receipt_line_id"),decimal(line,"base_qty"));
            if(hold!=null)holds.put(lineId,hold);
        }
        inventory.lockKeys(scope,lines.stream().map(line->new Key(warehouse,id(line,"sku_id"))).toList());
        boolean inclusive=false;
        if(!returning) {
            var policies=jdbc.queryForList("SELECT taxpayer_type FROM company_policy WHERE tenant_id=? AND company_id=?",String.class,scope.tenantId(),scope.companyId());
            inclusive=!policies.isEmpty()&&policies.getFirst().equals("small");
            jdbc.update("UPDATE goods_receipt SET cost_tax_inclusive=? WHERE id=?",inclusive,id);
        }
        BigDecimal amount=ZERO,tax=ZERO,cost=ZERO;var journal=new ArrayList<LedgerService.Line>();
        for(var line:lines) {
            var original=originalSource(scope,returning,id(line,source(returning)),true);validateSource(scope,returning,supplier,warehouse,day,original,true);
            BigDecimal[] posted=returning?postReturnLine(scope,id,supplier,warehouse,day,period,line,original,holds.get(id(line,"id")),journal):postReceiptLine(scope,id,supplier,warehouse,day,period,line,original,inclusive,journal);
            amount=amount.add(posted[0]);tax=tax.add(posted[1]);cost=cost.add(posted[2]);
        }
        if(!returning) {var fees=postFees(scope,id,supplier,day,period,lines,inclusive,journal);amount=amount.add(fees[0]);tax=tax.add(fees[1]);}
        postJournal(scope,table(returning),id,period,day,journal);
        jdbc.update("UPDATE "+table(returning)+" SET status='posted',"+(returning?"debit_amount":"payable_amount")+"=?,tax_amount=?,untaxed_amount=?,cost_amount=?,period_id=?,posted_at=UTC_TIMESTAMP(3),posted_by=?,version=version+1,updated_by=? WHERE id=?",
                amount,tax,amount.subtract(tax),cost,period,scope.userId(),scope.userId(),id);
        refresh(scope,returning,lines);
    }
    private BigDecimal[] postReceiptLine(CompanyAccess scope,long header,long supplier,long warehouse,LocalDate day,long period,
            Map<String,Object> line,Map<String,Object> original,boolean inclusive,List<LedgerService.Line> journal) {
        long lineId=id(line,"id");BigDecimal qty=decimal(line,"base_qty"),previous=decimal(original,"received_base_qty"),total=decimal(original,"base_qty");
        if(decimal(original,"pending_receive_base_qty").compareTo(qty)<0) {throw new ApiException(409,"STATE_CONFLICT","采购待收占用不一致");}
        BigDecimal amount=part(decimal(original,"payable_net"),total,previous,previous.add(qty));
        BigDecimal tax=part(decimal(original,"tax_amount"),total,previous,previous.add(qty));BigDecimal cost=inclusive?amount:amount.subtract(tax);
        long sku=id(line,"sku_id");
        var posting=new Posting(new Key(warehouse,sku),new Source("goods_receipt_line",header,lineId),day,period,"purchase_in");
        boolean inspection=quality.requires(scope,sku,supplier);
        var result=inventory.receive(scope,posting,qty,cost,inventory.decodeSelection(line.get("stock_selection")),inspection?"quality_hold":null,supplier);
        if(inspection) {
            var coordinates=inventory.postedSelection(scope,posting.source(),"purchase_in");
            quality.received(scope,header,lineId,sku,supplier,warehouse,
                    coordinates==null||coordinates.batchId()==null?"":coordinates.batchId().toString(),
                    coordinates==null||coordinates.locationId()==null?"":coordinates.locationId().toString(),qty,day);
        }
        jdbc.update("UPDATE goods_receipt_line SET payable_amount=?,tax_amount=?,untaxed_amount=?,unit_cost=?,cost_amount=?,version=version+1,updated_by=? WHERE id=?",amount,tax,amount.subtract(tax),result.unitCost(),cost,scope.userId(),lineId);
        jdbc.update("UPDATE purchase_order_line SET pending_receive_base_qty=pending_receive_base_qty-?,received_base_qty=received_base_qty+?,recognized_amount=recognized_amount+?,recognized_tax=recognized_tax+?,version=version+1,updated_by=? WHERE id=?",qty,qty,amount,tax,scope.userId(),original.get("id"));
        long open=payable(scope,supplier,"charge","goods_receipt_line",header,lineId,amount,tax,day,period);
        if(open!=0) {settlement.autoAllocatePurchaseOrder(scope,id(original,"order_id"),open);}
        add(journal,"INVENTORY","D",cost,null,warehouse,id(line,"sku_id"),lineId);
        add(journal,"INPUT_VAT","D",inclusive?ZERO:tax,null,null,null,lineId);
        add(journal,"AP","C",amount,supplier,null,null,lineId);return new BigDecimal[]{amount,tax,cost};
    }
    private BigDecimal[] postFees(CompanyAccess scope,long receipt,long supplier,LocalDate day,long period,List<Map<String,Object>> lines,boolean inclusive,List<LedgerService.Line> journal) {
        var orderIds=orderIds(scope,false,lines);BigDecimal amount=ZERO,tax=ZERO;
        for(long order:orderIds) {
            var fees=jdbc.queryForList("SELECT * FROM purchase_order_fee WHERE tenant_id=? AND company_id=? AND order_id=? AND bearer='buyer' AND recognized_receipt_id IS NULL ORDER BY id FOR UPDATE",scope.tenantId(),scope.companyId(),order);
            for(var fee:fees) {
                BigDecimal gross=decimal(fee,"total_amount"),vat=decimal(fee,"tax_amount");long id=id(fee,"id");
                jdbc.update("UPDATE purchase_order_fee SET recognized_receipt_id=?,version=version+1,updated_by=? WHERE id=?",receipt,scope.userId(),id);
                long open=payable(scope,supplier,"charge","purchase_order_fee",receipt,id,gross,vat,day,period);if(open!=0) {settlement.autoAllocatePurchaseOrder(scope,order,open);}
                add(journal,"PURCHASE_FEE","D",inclusive?gross:gross.subtract(vat),null,null,null,id);
                add(journal,"INPUT_VAT","D",inclusive?ZERO:vat,null,null,null,id);add(journal,"AP","C",gross,supplier,null,null,id);
                amount=amount.add(gross);tax=tax.add(vat);
            }
        }return new BigDecimal[]{amount,tax};
    }
    private BigDecimal[] postReturnLine(CompanyAccess scope,long header,long supplier,long warehouse,LocalDate day,long period,
            Map<String,Object> line,Map<String,Object> original,ReceiptQualityPort.Hold hold,List<LedgerService.Line> journal) {
        long lineId=id(line,"id");BigDecimal qty=decimal(line,"base_qty"),previous=decimal(original,"returned_base_qty"),total=decimal(original,"base_qty");
        if(decimal(original,"pending_return_base_qty").compareTo(qty)<0||previous.add(qty).compareTo(total)>0) {throw new ApiException(422,"RETURN_QTY_EXCEEDED","原收货可退数量不足");}
        BigDecimal amount=part(decimal(original,"payable_amount"),total,previous,previous.add(qty)),tax=part(decimal(original,"tax_amount"),total,previous,previous.add(qty));
        long originalLine=id(original,"id");
        var origin=new Source("goods_receipt_line",id(original,"receipt_id"),originalLine);
        var posting=new Posting(new Key(warehouse,id(line,"sku_id")),new Source("purchase_return_line",header,lineId),day,period,"purchase_return_out");
        var selected=inventory.returnSelection(scope,posting.key(),origin,"purchase_in",inventory.decodeSelection(line.get("stock_selection")),qty,
                hold==null?null:"quality_hold");
        var result=inventory.purchaseReturn(scope,posting,qty,decimal(original,"unit_cost"),selected,
                hold==null?null:new Source("goods_receipt_line",hold.sourceId(),hold.lineId()));
        BigDecimal cost=result.movementCost().subtract(result.adjustment());
        jdbc.update("UPDATE purchase_return_line SET debit_amount=?,tax_amount=?,untaxed_amount=?,unit_cost=?,cost_amount=?,cost_adjust_amount=?,version=version+1,updated_by=? WHERE id=?",amount,tax,amount.subtract(tax),original.get("unit_cost"),cost,result.adjustment(),scope.userId(),lineId);
        jdbc.update("UPDATE goods_receipt_line SET pending_return_base_qty=pending_return_base_qty-?,returned_base_qty=returned_base_qty+?,debited_amount=debited_amount+?,debited_tax=debited_tax+?,version=version+1,updated_by=? WHERE id=?",qty,qty,amount,tax,scope.userId(),original.get("id"));
        jdbc.update("UPDATE purchase_order_line SET returned_base_qty=returned_base_qty+?,version=version+1,updated_by=? WHERE id=?",qty,scope.userId(),original.get("order_line_id"));
        payable(scope,supplier,"credit","purchase_return_line",header,lineId,amount,tax,day,period);
        boolean inclusive=original.get("cost_tax_inclusive")==null?decimal(original,"cost_amount").compareTo(decimal(original,"payable_amount"))==0:original.get("cost_tax_inclusive") instanceof Boolean flag?flag:((Number)original.get("cost_tax_inclusive")).intValue()!=0;
        BigDecimal vat=inclusive?ZERO:tax;
        add(journal,"AP","D",amount,supplier,null,null,lineId);add(journal,"INPUT_VAT","C",vat,null,null,null,lineId);
        add(journal,"INVENTORY","C",result.movementCost(),null,warehouse,id(line,"sku_id"),lineId);
        add(journal,"INVENTORY","D",result.adjustment(),null,warehouse,id(line,"sku_id"),lineId);
        add(journal,"COST_VARIANCE","C",amount.subtract(vat).subtract(cost),null,null,null,lineId);
        return new BigDecimal[]{amount,tax,cost};
    }
    private long payable(CompanyAccess scope,long supplier,String kind,String source,long header,long line,BigDecimal amount,BigDecimal tax,LocalDate day,long period) {
        if(amount.signum()==0) {return 0;}
        long id=Ids.next();jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,scope.tenantId(),scope.companyId(),supplier,kind,source,header,line,amount,amount.subtract(tax),tax,day,period,scope.userId());
        return settlement.open(scope,new SettlementService.OpenItem("payable",id,supplier,kind.equals("charge")?"C":"D",amount,day));
    }
    private Set<Long> orderIds(CompanyAccess scope,boolean returning,List<Map<String,Object>> lines) {
        var ids=new TreeSet<Long>();for(var line:lines) {ids.add(id(originalSource(scope,returning,id(line,source(returning)),false),"order_id"));}return ids;
    }
    private void refresh(CompanyAccess scope,boolean returning,List<Map<String,Object>> lines) {for(long id:orderIds(scope,returning,lines)) {orders.refreshProgress(scope,id);}}
    private long period(CompanyAccess scope,LocalDate day) {
        date(day);var rows=jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",Long.class,scope.tenantId(),scope.companyId(),day,day);
        if(rows.size()!=1) {throw new ApiException(422,"PERIOD_CLOSED","业务日期没有对应的开放期间");}ledger.lockOpenPeriod(scope,rows.getFirst(),day);return rows.getFirst();
    }
    private static BigDecimal part(BigDecimal amount,BigDecimal total,BigDecimal previous,BigDecimal cumulative) {return amount.multiply(cumulative).divide(total,2,RoundingMode.HALF_UP).subtract(amount.multiply(previous).divide(total,2,RoundingMode.HALF_UP));}
    private void status(CompanyAccess scope,boolean returning,long id,String status,String timestamp) {jdbc.update("UPDATE "+table(returning)+" SET status=?,"+timestamp+"=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=?",status,scope.userId(),id);}
    private void add(List<LedgerService.Line> lines,String account,String direction,BigDecimal amount,Long supplier,Long warehouse,Long sku,long source) {
        if(amount.signum()==0) {return;}lines.add(new LedgerService.Line(account,amount.signum()>0?direction:direction.equals("D")?"C":"D",amount.abs(),supplier,null,warehouse,sku,source,"采购收退货"));
    }
    private void postJournal(CompanyAccess scope,String type,long id,long period,LocalDate day,List<LedgerService.Line> entries) {
        int start=0,revision=0;
        while(start<entries.size()) {
            int end=-1;BigDecimal balance=ZERO;
            for(int index=start;index<Math.min(start+1000,entries.size());index++) {var line=entries.get(index);balance=balance.add(line.direction().equals("D")?line.amount():line.amount().negate());if(balance.signum()==0) {end=index+1;}}
            if(end<0) {throw new ApiException(409,"JOURNAL_UNBALANCED","采购分录不能平衡，请先核对");}
            ledger.post(scope,new LedgerService.Posting(type,id,revision++,period,day,entries.subList(start,end)));start=end;
        }
    }
    private Map<String,Object> safe(CompanyAccess scope,boolean returning,long id) {var permissions=new HashSet<>(scope.permissions());permissions.remove("cost:view");return queries.get(new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),scope.allWarehouses(),scope.warehouseIds(),permissions,scope.orgScope()),returning,id);}
    @SuppressWarnings({"unchecked","rawtypes"}) private Map<String,Object> execute(CompanyAccess scope,boolean returning,String action,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {return commands.execute(scope,table(returning)+"."+action,key,payload,status,work,(Class)Map.class);}
}
