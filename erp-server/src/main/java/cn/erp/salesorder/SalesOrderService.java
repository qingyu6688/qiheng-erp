package cn.erp.salesorder;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.pricing.*;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.salesorder.SalesOrderQueries.*;

@Service
@Transactional
public class SalesOrderService {
    private final JdbcTemplate jdbc;
    private final SalesOrderQueries queries;
    private final SalesOrderWriter writer;
    private final BusinessCommands commands;
    private final DocumentNumbers numbers;
    private final ReservationService reservations;
    private final SalesOrderReservations orderReservations;
    private final InventoryService inventory;
    private final CatalogAvailability catalog;
    private final ReferenceAvailability partners;
    private final AuditService audit;
    private final ObjectMapper json;

    public SalesOrderService(JdbcTemplate jdbc,SalesOrderQueries queries,SalesOrderWriter writer,BusinessCommands commands,
            DocumentNumbers numbers,ReservationService reservations,InventoryService inventory,CatalogAvailability catalog,
            ReferenceAvailability partners,AuditService audit,ObjectMapper json,SalesOrderReservations orderReservations) {
        this.jdbc=jdbc;this.queries=queries;this.writer=writer;this.commands=commands;this.numbers=numbers;
        this.reservations=reservations;this.inventory=inventory;this.catalog=catalog;this.partners=partners;this.audit=audit;this.json=json;
        this.orderReservations=orderReservations;
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> execute(CompanyAccess scope,String operation,String key,Object payload,int status,java.util.function.Supplier<Map<String,Object>> work) {
        return commands.execute(scope,"sales_order."+operation,key,payload,status,work,(Class)Map.class);
    }

    public Map<String,Object> create(CompanyAccess scope,String key,SalesOrderModels.Input input) {
        return execute(scope,"create",key,input,201,()->{
            var preview=writer.price(scope,input);
            long id=Ids.next();String no=numbers.next(scope,"SO",YearMonth.from(input.businessDate()));
            jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,created_by,updated_by) VALUES(?,?,?,?,?,?,1,?,?)",
                    id,scope.tenantId(),scope.companyId(),no,identifier(input.customerId()),input.businessDate(),scope.userId(),scope.userId());
            writer.write(scope,id,input,preview);
            var result=queries.get(scope,id);
            audit.record(scope,"sales_order.create","sales_order",id,null,result);
            return result;
        });
    }

    public Map<String,Object> edit(CompanyAccess scope,long id,SalesOrderModels.Input input) {
        var before=queries.header(scope,id,true);version(input.version(),before);state(before,"draft");
        var preview=writer.price(scope,input);
        writer.write(scope,id,input,preview);touch(scope,id);
        var result=queries.get(scope,id);audit.record(scope,"sales_order.update","sales_order",id,camel(before),result);return result;
    }

    public void delete(CompanyAccess scope,long id,long expectedVersion) {
        var rows=jdbc.queryForList("SELECT * FROM sales_order WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}
        var before=rows.getFirst();
        if(flag(before.get("is_deleted"))) {return;}
        queries.visible(scope,id);version(expectedVersion,before);state(before,"draft");
        jdbc.update("UPDATE sales_order SET is_deleted=1,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);
        audit.record(scope,"sales_order.delete","sales_order",id,camel(before),null);
    }

    public Map<String,Object> command(CompanyAccess scope,long id,String action,String key,SalesOrderModels.Command input) {
        queries.header(scope,id,false);
        return execute(scope,action,key,List.of(id,input),200,()->{
            var before=queries.header(scope,id,true);version(input.version(),before);
            var rows=queries.lines(scope,id,true);
            switch(action) {
                case "submit" -> {state(before,"draft");active(scope,before,rows,false);changeState(scope,id,"pending");approval(scope,id,before,"submit",input.comment());}
                case "withdraw" -> {state(before,"pending");changeState(scope,id,"draft");approval(scope,id,before,"withdraw",input.comment());}
                case "reject" -> {state(before,"pending");reason(input.comment());changeState(scope,id,"draft");approval(scope,id,before,"reject",input.comment());}
                case "approve" -> {
                    state(before,"pending");separateApprover(scope,before);active(scope,before,rows,true);reserveRows(scope,before,rows);
                    jdbc.update("UPDATE sales_order SET status='approved',revision=revision+1,approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE id=? AND tenant_id=? AND company_id=?",
                            scope.userId(),scope.userId(),id,scope.tenantId(),scope.companyId());
                    approval(scope,id,before,"approve",input.comment());
                }
                case "revoke_approval" -> {
                    state(before,"approved");assertNoShipment(scope,id);
                    release(scope,rows,"release_revoke",new Source("sales_order",id,Ids.next()),null);
                    jdbc.update("UPDATE sales_order SET status='draft',approved_by=NULL,approved_at=NULL,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);
                    approval(scope,id,before,"revoke",input.comment());
                }
                case "cancel" -> {
                    state(before,"approved");reason(input.reason());
                    if(rows.stream().anyMatch(r->decimal(r,"shipped_base_qty").signum()>0||decimal(r,"pending_ship_base_qty").signum()>0)) {
                        throw new ApiException(422,"DEPENDENCY_EXISTS","已有已发或待发数量，请先处理发货单或关闭剩余数量");
                    }
                    release(scope,rows,"release_cancel",new Source("sales_order",id,Ids.next()),null);
                    jdbc.update("UPDATE sales_order SET status='cancelled',cancel_reason=?,cancelled_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                            input.reason(),scope.userId(),scope.tenantId(),scope.companyId(),id);
                }
                case "reserve" -> {state(before,"approved");active(scope,before,rows,true);reserveRows(scope,before,rows);touch(scope,id);}
                default -> throw invalid("订单操作无效");
            }
            var result=summary(scope,id);
            if(action.equals("submit")) {result.put("warnings",warnings(scope,id));}
            audit.record(scope,"sales_order."+action,"sales_order",id,camel(before),result);
            return result;
        });
    }

    public Map<String,Object> close(CompanyAccess scope,long id,String key,SalesOrderModels.Close input) {
        queries.header(scope,id,false);
        return execute(scope,"close_lines",key,List.of(id,input),200,()->{
            var before=queries.header(scope,id,true);version(input.version(),before);state(before,"approved");reason(input.reason());
            var rows=queries.lines(scope,id,true);var byId=new HashMap<Long,Map<String,Object>>();rows.forEach(r->byId.put(number(r,"id"),r));
            var selected=new LinkedHashMap<Long,BigDecimal>();
            for(var line:input.lines()) {
                long lineId=identifier(line.lineId());BigDecimal qty=quantity(line.closeQty());
                var row=byId.get(lineId);if(row==null||selected.put(lineId,qty)!=null) {throw invalid("关闭明细不属于当前订单或重复");}
                BigDecimal free=decimal(row,"base_qty").subtract(decimal(row,"closed_base_qty")).subtract(decimal(row,"shipped_base_qty")).subtract(decimal(row,"pending_ship_base_qty"));
                if(qty.compareTo(free)>0) {throw new ApiException(422,"CLOSE_QTY_EXCEEDED","关闭数量超过可发剩余数量");}
            }
            release(scope,rows,"release_close",new Source("sales_order",id,Ids.next()),selected);
            for(var selectedLine:selected.entrySet()) {
                jdbc.update("UPDATE sales_order_line SET closed_base_qty=closed_base_qty+?,close_reason=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                        selectedLine.getValue(),input.reason(),scope.userId(),scope.tenantId(),scope.companyId(),selectedLine.getKey());
            }
            touch(scope,id);queries.refreshProgress(scope,id);var result=summary(scope,id);
            audit.record(scope,"sales_order.close_lines","sales_order",id,camel(before),result);return result;
        });
    }

    public Map<String,Object> change(CompanyAccess scope,long id,String key,SalesOrderModels.Input input) {
        queries.header(scope,id,false);
        return execute(scope,"change",key,List.of(id,input),200,()->{
            var before=queries.header(scope,id,true);version(input.version(),before);state(before,"approved","closed");reason(input.reason());
            var rows=queries.lines(scope,id,true);var preview=writer.price(scope,input);
            if(number(before,"customer_id")!=identifier(input.customerId())||flag(before.get("price_tax_inclusive"))!=preview.priceTaxInclusive()) {
                throw new ApiException(422,"CHANGE_NOT_ALLOWED","审批后的订单不能更换客户或含税价格口径");
            }
            boolean approval=requiresApproval(scope,before,rows,input,preview);
            long changeId=Ids.next();String no=numbers.next(scope,"CH",YearMonth.from(input.businessDate()));
            jdbc.update("""
                    INSERT INTO document_change(id,tenant_id,company_id,change_no,document_type,document_id,from_revision,to_revision,
                      requires_approval,status,before_snapshot,after_snapshot,reason,created_by)
                    VALUES(?,?,?,?,'sales_order',?,?,?,?,?,?,?,?,?)
                    """,changeId,scope.tenantId(),scope.companyId(),no,id,number(before,"revision"),number(before,"revision")+1,approval,
                    approval?"pending":"applied",json.writeValueAsString(queries.get(scope,id)),json.writeValueAsString(input),input.reason(),scope.userId());
            if(approval) {changeState(scope,id,"change_pending");}
            else {applyChange(scope,id,before,rows,input,preview,changeId);}
            var result=summary(scope,id);
            result.put("change",Map.of("id",Long.toString(changeId),"changeNo",no,"status",approval?"pending":"applied","requiresApproval",approval,"version",0));
            audit.record(scope,"sales_order.change","sales_order",id,camel(before),result);return result;
        });
    }

    public Map<String,Object> decideChange(CompanyAccess scope,long changeId,String action,String key,SalesOrderModels.Command input) {
        return execute(scope,"change_"+action,key,List.of(changeId,input),200,()->{
            var changes=jdbc.queryForList("SELECT * FROM document_change WHERE tenant_id=? AND company_id=? AND id=? AND document_type='sales_order'",scope.tenantId(),scope.companyId(),changeId);
            if(changes.isEmpty()) {throw missing();}
            long id=number(changes.getFirst(),"document_id");var before=queries.header(scope,id,true);state(before,"change_pending");
            var change=jdbc.queryForMap("SELECT * FROM document_change WHERE id=? FOR UPDATE",changeId);
            version(input.version(),change);
            if(!change.get("status").equals("pending")) {throw conflict("变更单已处理");}
            if(action.equals("approve")) {
                var applicant=new LinkedHashMap<>(before);applicant.put("created_by",change.get("created_by"));separateApprover(scope,applicant);
                var after=json.readValue(change.get("after_snapshot").toString(),SalesOrderModels.Input.class);
                var preview=writer.price(scope,after);var lines=queries.lines(scope,id,true);
                requiresApproval(scope,before,lines,after,preview);
                active(scope,before,lines,false);applyChange(scope,id,before,lines,after,preview,changeId);
            } else {reason(input.comment());changeState(scope,id,"approved");queries.refreshProgress(scope,id);}
            jdbc.update("UPDATE document_change SET status=?,decided_by=?,decided_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",action.equals("approve")?"applied":"rejected",scope.userId(),changeId);
            var result=summary(scope,id);result.put("change",Map.of("id",Long.toString(changeId),"status",action.equals("approve")?"applied":"rejected","version",number(change,"version")+1));
            audit.record(scope,"sales_order.change_"+action,"document_change",changeId,camel(change),result);return result;
        });
    }

    private void applyChange(CompanyAccess scope,long id,Map<String,Object> before,List<Map<String,Object>> rows,
            SalesOrderModels.Input input,PricingModels.Preview preview,long changeId) {
        writer.write(scope,id,input,preview);
        jdbc.update("UPDATE sales_order SET status='approved',revision=revision+1,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);
        var current=queries.header(scope,id,false);var lines=queries.lines(scope,id,true);active(scope,current,lines,true);
        boolean deliveryChanged=!Objects.equals(date(before.get("delivery_date")),input.deliveryDate());
        orderReservations.adjust(scope,rows,lines,changeId,expiry(scope,current),deliveryChanged);queries.refreshProgress(scope,id);
    }

    private boolean requiresApproval(CompanyAccess scope,Map<String,Object> before,List<Map<String,Object>> rows,SalesOrderModels.Input input,PricingModels.Preview preview) {
        boolean approval=decimal(before,"order_discount_amount").compareTo(new BigDecimal(preview.orderDiscountAmount()))!=0;
        Set<Long> used=new HashSet<>();
        for(int i=0;i<preview.lines().size();i++) {
            var source=input.lines().get(i);var current=preview.lines().get(i);
            Long id;
            if(source.id()!=null) {id=identifier(source.id());}
            else if(i<rows.size()) {id=number(rows.get(i),"id");}
            else {id=null;}
            Map<String,Object> old=id==null?null:rows.stream().filter(r->number(r,"id")==id).findFirst().orElse(null);
            if(old==null) {if(source.id()!=null) {throw invalid("变更明细不属于当前订单");}approval=true;continue;}
            if(!used.add(id)) {throw invalid("变更订单行不能重复");}
            BigDecimal quantity=new BigDecimal(current.baseQty());
            if(quantity.compareTo(decimal(old,"shipped_base_qty").add(decimal(old,"pending_ship_base_qty")).add(decimal(old,"closed_base_qty")))<0) {
                throw new ApiException(422,"CHANGE_NOT_ALLOWED","变更数量不能低于已发、待发与已关闭数量");
            }
            boolean priceChanged=new BigDecimal(current.unitPrice()).compareTo(decimal(old,"unit_price"))!=0 ||
                    new BigDecimal(current.taxRate()).compareTo(decimal(old,"tax_rate"))!=0 ||
                    new BigDecimal(current.lineDiscountAmount()).compareTo(decimal(old,"line_discount"))!=0 ||
                    number(old,"sku_id")!=identifier(current.skuId()) || number(old,"unit_id")!=identifier(current.unitId());
            if(decimal(old,"shipped_base_qty").signum()>0 && priceChanged) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","已发货的订单行不能改价、税率或商品单位");}
            if(decimal(old,"shipped_base_qty").signum()>0 && new BigDecimal(current.receivableNet()).divide(quantity,16,java.math.RoundingMode.HALF_UP)
                    .compareTo(decimal(old,"receivable_net").divide(decimal(old,"base_qty"),16,java.math.RoundingMode.HALF_UP))!=0) {
                throw new ApiException(422,"CHANGE_NOT_ALLOWED","变更会改变已发货行的分摊后单价，请使用关闭剩余数量");
            }
            if(decimal(old,"pending_ship_base_qty").signum()>0 && !Objects.equals(SalesOrderWriter.nullableId(current.warehouseId()),old.get("warehouse_id")==null?null:number(old,"warehouse_id"))) {
                throw new ApiException(422,"DEPENDENCY_EXISTS","请先作废该行待发货单，再更换发货仓库");
            }
            if(priceChanged || quantity.compareTo(decimal(old,"base_qty"))>0) {approval=true;}
        }
        for(var old:rows) {if(!used.contains(number(old,"id"))&&(decimal(old,"shipped_base_qty").signum()>0||decimal(old,"pending_ship_base_qty").signum()>0)) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","已发或待发的订单行不能删除");}}
        var fees=jdbc.queryForList("SELECT fee_type,bearer,amount,tax_amount,tax_rate,price_tax_inclusive,recognized_shipment_id FROM sales_order_fee WHERE order_id=? ORDER BY id",number(before,"id"));
        boolean feeChanged=fees.size()!=preview.fees().size();
        if(feeChanged) {approval=true;}
        else {for(int i=0;i<fees.size();i++) {var fee=fees.get(i);var current=preview.fees().get(i);
            if(!fee.get("fee_type").equals(current.feeType())||!fee.get("bearer").equals(current.bearer())||
                decimal(fee,"amount").compareTo(new BigDecimal(current.amount()))!=0||decimal(fee,"tax_amount").compareTo(new BigDecimal(current.taxAmount()))!=0||
                decimal(fee,"tax_rate").compareTo(new BigDecimal(current.taxRate()))!=0||flag(fee.get("price_tax_inclusive"))!=current.priceTaxInclusive()) {approval=true;feeChanged=true;}
        }}
        if(feeChanged&&fees.stream().anyMatch(fee->fee.get("recognized_shipment_id")!=null)) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","已经确认的费用不能修改或增减");}
        return approval;
    }

    private void active(CompanyAccess scope,Map<String,Object> order,List<Map<String,Object>> rows,boolean warehouseRequired) {
        partners.requirePartner(scope,number(order,"customer_id"),"customer");
        for(var row:rows) {
            catalog.requireActiveSku(scope,number(row,"sku_id"),number(row,"unit_id"));
            if(row.get("warehouse_id")==null) {if(warehouseRequired) {throw invalid("审批前请为每行指定发货仓库");}continue;}
            long warehouse=number(row,"warehouse_id");
            if(!scope.allowsWarehouse(warehouse)) {throw missing();}
            if(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Long.class,scope.tenantId(),scope.companyId(),warehouse)==0) {throw new ApiException(422,"MASTER_DATA_DISABLED","发货仓库不存在或已停用");}
        }
    }

    private void reserveRows(CompanyAccess scope,Map<String,Object> order,List<Map<String,Object>> rows) {
        orderReservations.reserveAll(scope,rows,expiry(scope,order),number(order,"id"));
    }

    private LocalDate date(Object value) {return value==null?null:value instanceof java.sql.Date day?day.toLocalDate():(LocalDate)value;}

    private Instant expiry(CompanyAccess scope,Map<String,Object> order) {
        var policy=jdbc.queryForList("SELECT reservation_valid_days,reservation_grace_days FROM company_policy WHERE tenant_id=? AND company_id=?",scope.tenantId(),scope.companyId());
        int valid=policy.isEmpty()?30:((Number)policy.getFirst().get("reservation_valid_days")).intValue();
        int grace=policy.isEmpty()?7:((Number)policy.getFirst().get("reservation_grace_days")).intValue();
        Instant due=Instant.now().plus(java.time.Duration.ofDays(valid));
        if(order.get("delivery_date")!=null) {
            LocalDate date=order.get("delivery_date") instanceof java.sql.Date d?d.toLocalDate():(LocalDate)order.get("delivery_date");
            Instant requested=date.plusDays(grace).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            if(requested.isAfter(due)) {due=requested;}
        }
        return due;
    }

    private void release(CompanyAccess scope,List<Map<String,Object>> rows,String reason,Source source,Map<Long,BigDecimal> partial) {
        var keys=rows.stream().filter(r->r.get("warehouse_id")!=null).map(r->new Key(number(r,"warehouse_id"),number(r,"sku_id"))).toList();
        inventory.lockKeys(scope,keys);
        for(var line:rows) {
            if(partial!=null&&!partial.containsKey(number(line,"id"))) {continue;}
            var active=jdbc.queryForList("SELECT id,remaining_qty FROM inventory_reservation WHERE tenant_id=? AND company_id=? AND order_line_id=? AND status='active' ORDER BY id FOR UPDATE",scope.tenantId(),scope.companyId(),number(line,"id"));
            for(var row:active) {
                BigDecimal qty=partial==null?decimal(row,"remaining_qty"):partial.get(number(line,"id")).min(decimal(row,"remaining_qty"));
                if(qty.signum()>0) {reservations.release(scope,number(row,"id"),qty,reason,new Source(source.type(),source.id(),source.lineId()+number(line,"line_no")));}
            }
        }
    }

    private List<Map<String,Object>> warnings(CompanyAccess scope,long id) {
        var result=new ArrayList<Map<String,Object>>();
        for(var row:queries.lines(scope,id,false)) {if(row.get("warehouse_id")!=null) {
            var key=new Key(number(row,"warehouse_id"),number(row,"sku_id"));var stock=inventory.balance(scope,key);
            if(stock.available().compareTo(decimal(row,"base_qty"))<0) {
                var warning=new LinkedHashMap<String,Object>();warning.put("type","INVENTORY_MAY_BE_INSUFFICIENT");warning.put("lineId",row.get("id").toString());
                warning.put("lineNo",row.get("line_no"));warning.put("requiredQty",row.get("base_qty").toString());warning.put("availableQty",stock.available().toPlainString());
                warning.put("pendingDemandQty",pendingDemand(scope,key,id).toPlainString());warning.put("message","当前库存不足，审批时可能失败");result.add(warning);
            }
        }}
        return result;
    }

    public BigDecimal pendingDemand(CompanyAccess scope,Key key,long excludedOrder) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(l.base_qty),0) FROM sales_order_line l JOIN sales_order h ON h.id=l.order_id AND h.tenant_id=l.tenant_id AND h.company_id=l.company_id
            WHERE l.tenant_id=? AND l.company_id=? AND l.warehouse_id=? AND l.sku_id=? AND l.is_deleted=0 AND h.is_deleted=0 AND h.status='pending' AND h.id<>?
            """,BigDecimal.class,scope.tenantId(),scope.companyId(),key.warehouseId(),key.skuId(),excludedOrder);
    }

    private void assertNoShipment(CompanyAccess scope,long id) {
        Long count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM shipment_line l JOIN shipment h ON h.id=l.shipment_id JOIN sales_order_line o ON o.id=l.order_line_id
            WHERE o.tenant_id=? AND o.company_id=? AND o.order_id=? AND h.status<>'voided'
            """,Long.class,scope.tenantId(),scope.companyId(),id);
        if(count>0) {throw new ApiException(422,"DEPENDENCY_EXISTS","存在下游发货单，请先处理");}
    }

    private void separateApprover(CompanyAccess scope,Map<String,Object> row) {
        var policies=jdbc.queryForList("SELECT separate_order_approver FROM company_policy WHERE tenant_id=? AND company_id=?",Integer.class,scope.tenantId(),scope.companyId());
        if(row.get("created_by")!=null&&number(row,"created_by")==scope.userId()&&(policies.isEmpty()||policies.getFirst()!=0)) {
            throw new ApiException(422,"SAME_OPERATOR_NOT_ALLOWED","审批人须与制单人分离");
        }
    }
    private void approval(CompanyAccess scope,long id,Map<String,Object> row,String action,String comment) {
        jdbc.update("INSERT INTO approval_record(id,tenant_id,company_id,document_type,document_id,revision,action,operator_id,comment) VALUES(?,?,?,'sales_order',?,?,?,?,?)",
                Ids.next(),scope.tenantId(),scope.companyId(),id,number(row,"revision"),action,scope.userId(),comment);
    }
    private void changeState(CompanyAccess scope,long id,String state) {
        jdbc.update("UPDATE sales_order SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",state,scope.userId(),scope.tenantId(),scope.companyId(),id);
    }
    private void touch(CompanyAccess scope,long id) {jdbc.update("UPDATE sales_order SET version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",scope.userId(),scope.tenantId(),scope.companyId(),id);}
    private Map<String,Object> summary(CompanyAccess scope,long id) {var row=queries.header(scope,id,false);return new LinkedHashMap<>(Map.of("id",Long.toString(id),"documentNo",row.get("order_no"),"status",row.get("status"),"version",row.get("version"),"revision",row.get("revision")));}
    private void version(Long requested,Map<String,Object> row) {if(requested==null||requested<0) {throw invalid("请提供有效版本号");}if(requested!=number(row,"version")) {throw new ApiException(409,"VERSION_CONFLICT","订单已被他人修改，请刷新",Map.of("currentVersion",row.get("version")));}}
    private void state(Map<String,Object> row,String... states) {if(!Arrays.asList(states).contains(row.get("status"))) {throw conflict("当前订单状态不允许执行此操作");}}
    private void reason(String text) {if(text==null||text.isBlank()||text.length()>500) {throw invalid("请填写原因或审批意见");}}
    private boolean flag(Object value) {return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    private BigDecimal quantity(String value) {if(value==null||!value.matches("[0-9]+(?:\\.[0-9]{1,6})?")||value.length()>26) {throw invalid("数量格式或精度无效");}var number=new BigDecimal(value);if(number.signum()<=0||number.precision()-number.scale()>18) {throw invalid("数量必须大于零且在允许范围内");}return number.setScale(6);}
}
