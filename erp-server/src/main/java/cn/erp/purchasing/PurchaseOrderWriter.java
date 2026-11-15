package cn.erp.purchasing;

import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.*;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static cn.erp.purchasing.PurchaseOrderQueries.*;

@Service
class PurchaseOrderWriter {
    private final JdbcTemplate jdbc;
    private final PricingService pricing;
    private final RequisitionLinks links;
    PurchaseOrderWriter(JdbcTemplate jdbc,PricingService pricing,RequisitionLinks links) {this.jdbc=jdbc;this.pricing=pricing;this.links=links;}

    PricingModels.Preview price(CompanyAccess scope,PurchaseOrderModels.Input input) {
        if(input.businessDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {throw invalid("业务日期不能晚于今天");}
        if(input.deliveryDate()!=null && input.deliveryDate().isBefore(input.businessDate())) {throw invalid("交货日期不能早于业务日期");}
        var lines=input.lines().stream().map(l->new PricingModels.PreviewLine(l.skuId(),l.unitId(),l.warehouseId(),l.qty(),l.unitPrice(),l.amountInput(),l.lineDiscountAmount(),l.lineDiscountRatio(),l.taxRate())).toList();
        var fees=input.fees()==null?List.<PricingModels.Fee>of():input.fees().stream().map(f->new PricingModels.Fee(f.feeType(),f.bearer(),f.amount(),f.taxRate())).toList();
        // 订单修改权限包含对已有订单重算价格，接口入口仍校验实际操作权限。
        var permissions=new HashSet<>(scope.permissions());permissions.add("purchase_order:create");
        var pricingScope=new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),scope.allWarehouses(),scope.warehouseIds(),permissions);
        return pricing.preview(pricingScope,new PricingModels.PreviewRequest("purchase_order",null,input.supplierId(),input.priceTaxInclusive(),input.orderDiscountAmount(),lines,fees));
    }

    void write(CompanyAccess scope,long orderId,PurchaseOrderModels.Input input,PricingModels.Preview preview) {
        links.replace(scope,orderId,input,preview);
        jdbc.update("""
            UPDATE purchase_order SET supplier_id=?,business_date=?,delivery_date=?,price_tax_inclusive=?,pricing_version=2,
              goods_amount=?,line_discount_amount=?,order_discount_amount=?,fee_amount=?,total_amount=?,untaxed_amount=?,tax_amount=?,remark=?,updated_by=?
            WHERE tenant_id=? AND company_id=? AND id=?
            """,identifier(input.supplierId()),input.businessDate(),input.deliveryDate(),preview.priceTaxInclusive(),
            preview.goodsAmount(),preview.lineDiscountAmount(),preview.orderDiscountAmount(),preview.feeAmount(),preview.totalAmount(),preview.untaxedAmount(),preview.taxAmount(),
            input.remark(),scope.userId(),scope.tenantId(),scope.companyId(),orderId);
        var old=jdbc.queryForList("SELECT * FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0 ORDER BY id FOR UPDATE",scope.tenantId(),scope.companyId(),orderId);
        Map<Long,Map<String,Object>> oldById=new HashMap<>();old.forEach(r->oldById.put(number(r,"id"),r));
        Set<Long> kept=new HashSet<>();
        int last=jdbc.queryForObject("SELECT COALESCE(MAX(line_no),0) FROM purchase_order_line WHERE order_id=?",Integer.class,orderId);
        for(int i=0;i<preview.lines().size();i++) {
            var source=input.lines().get(i);var line=preview.lines().get(i);
            Long id=null;
            if(source.id()!=null) {id=identifier(source.id());}
            if(id==null && i<old.size()) {
                var candidate=old.get(i);
                if(number(candidate,"sku_id")==identifier(source.skuId()) && number(candidate,"unit_id")==identifier(source.unitId()) && !kept.contains(number(candidate,"id"))) {id=number(candidate,"id");}
            }
            if(id!=null && (!oldById.containsKey(id)||!kept.add(id))) {throw invalid("订单行不属于当前订单或重复");}
            if(id==null) {
                id=Ids.next();kept.add(id);
                jdbc.update("""
                    INSERT INTO purchase_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,requisition_line_id,
                      qty,base_qty,receive_limit_base_qty,unit_price,price_tax_inclusive,amount,line_discount,line_net,order_discount_alloc,payable_net,tax_rate,
                      untaxed_amount,tax_amount,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,scope.tenantId(),scope.companyId(),orderId,++last,identifier(line.skuId()),nullableId(line.warehouseId()),identifier(line.unitId()),line.unitRate(),nullableId(source.requisitionLineId()),
                    line.qty(),line.baseQty(),line.baseQty(),line.unitPrice(),line.priceTaxInclusive(),line.amount(),line.lineDiscountAmount(),line.lineNet(),line.orderDiscountAlloc(),
                    line.receivableNet(),line.taxRate(),line.untaxedAmount(),line.taxAmount(),scope.userId(),scope.userId());
            } else {
                var before=oldById.get(id);
                var minimum=decimal(before,"received_base_qty").add(decimal(before,"pending_receive_base_qty")).add(decimal(before,"closed_base_qty"));
                if(limit(before,line).compareTo(minimum)<0) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","订单行数量不能低于已收、待收和已关闭数量");}
                jdbc.update("""
                    UPDATE purchase_order_line SET sku_id=?,warehouse_id=?,unit_id=?,unit_rate=?,requisition_line_id=?,qty=?,base_qty=?,receive_limit_base_qty=?,unit_price=?,price_tax_inclusive=?,
                      amount=?,line_discount=?,line_net=?,order_discount_alloc=?,payable_net=?,tax_rate=?,untaxed_amount=?,tax_amount=?,
                      version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND order_id=? AND id=?
                    """,identifier(line.skuId()),nullableId(line.warehouseId()),identifier(line.unitId()),line.unitRate(),nullableId(source.requisitionLineId()),line.qty(),line.baseQty(),limit(before,line),line.unitPrice(),line.priceTaxInclusive(),
                    line.amount(),line.lineDiscountAmount(),line.lineNet(),line.orderDiscountAlloc(),line.receivableNet(),line.taxRate(),line.untaxedAmount(),line.taxAmount(),
                    scope.userId(),scope.tenantId(),scope.companyId(),orderId,id);
            }
        }
        for(var row:old) {if(!kept.contains(number(row,"id"))) {
            if(decimal(row,"received_base_qty").signum()>0 || decimal(row,"pending_receive_base_qty").signum()>0) {throw new ApiException(422,"CHANGE_NOT_ALLOWED","已收或待收的订单行不能删除");}
            jdbc.update("UPDATE purchase_order_line SET is_deleted=1,version=version+1 WHERE id=?",number(row,"id"));
        }}
        writeFees(scope,orderId,input,preview);
    }

    private void writeFees(CompanyAccess scope,long orderId,PurchaseOrderModels.Input input,PricingModels.Preview preview) {
        var old=jdbc.queryForList("SELECT * FROM purchase_order_fee WHERE tenant_id=? AND company_id=? AND order_id=? ORDER BY id FOR UPDATE",scope.tenantId(),scope.companyId(),orderId);
        boolean unchanged=old.size()==preview.fees().size();
        for(int i=0;unchanged&&i<old.size();i++) {unchanged=sameFee(old.get(i),preview.fees().get(i));}
        if(unchanged) {return;}
        var recognized=old.stream().filter(r->r.get("recognized_receipt_id")!=null).toList();
        if(!recognized.isEmpty()) {
            throw new ApiException(422,"CHANGE_NOT_ALLOWED","已经确认的费用不能修改或增减");
        }
        Map<Long,Map<String,Object>> byId=new HashMap<>();old.forEach(row->byId.put(number(row,"id"),row));
        Set<Long> kept=new HashSet<>();
        for(int i=0;i<preview.fees().size();i++) {
            var fee=preview.fees().get(i);String requested=input.fees().get(i).id();
            Long feeId=null;
            if(requested!=null) {feeId=identifier(requested);}
            else if(i<old.size()) {feeId=number(old.get(i),"id");}
            if(feeId!=null&&(!byId.containsKey(feeId)||!kept.add(feeId))) {throw invalid("费用行不属于当前订单或重复");}
            if(feeId==null) {
                jdbc.update("""
                INSERT INTO purchase_order_fee(id,tenant_id,company_id,order_id,fee_type,bearer,amount,price_tax_inclusive,tax_rate,untaxed_amount,tax_amount,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,Ids.next(),scope.tenantId(),scope.companyId(),orderId,fee.feeType(),fee.bearer(),fee.amount(),fee.priceTaxInclusive(),fee.taxRate(),fee.untaxedAmount(),fee.taxAmount(),scope.userId());
            } else {
                jdbc.update("UPDATE purchase_order_fee SET fee_type=?,bearer=?,amount=?,price_tax_inclusive=?,tax_rate=?,untaxed_amount=?,tax_amount=?,version=version+1,updated_by=? WHERE id=?",
                        fee.feeType(),fee.bearer(),fee.amount(),fee.priceTaxInclusive(),fee.taxRate(),fee.untaxedAmount(),fee.taxAmount(),scope.userId(),feeId);
            }
        }
        for(var row:old) {if(!kept.contains(number(row,"id"))) {jdbc.update("DELETE FROM purchase_order_fee WHERE id=? AND recognized_receipt_id IS NULL",number(row,"id"));}}
    }

    private boolean sameFee(Map<String,Object> old,PricingModels.FeeResult next) {
        boolean inclusive=old.get("price_tax_inclusive") instanceof Boolean flag?flag:((Number)old.get("price_tax_inclusive")).intValue()!=0;
        return old.get("fee_type").equals(next.feeType())&&old.get("bearer").equals(next.bearer())&&inclusive==next.priceTaxInclusive()
                &&decimal(old,"amount").compareTo(new BigDecimal(next.amount()))==0&&decimal(old,"tax_rate").compareTo(new BigDecimal(next.taxRate()))==0
                &&decimal(old,"tax_amount").compareTo(new BigDecimal(next.taxAmount()))==0;
    }

    private BigDecimal limit(Map<String,Object> before,PricingModels.LineResult line) {return new BigDecimal(line.baseQty()).multiply(decimal(before,"receive_limit_base_qty")).divide(decimal(before,"base_qty"),6,java.math.RoundingMode.DOWN);}

    static Long nullableId(String value) {if(value==null) {return null;}return identifier(value);}
}
