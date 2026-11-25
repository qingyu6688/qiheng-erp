package cn.erp.quotation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.crm.CrmScope;
import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.MinimumSalePrice;
import cn.erp.pricing.PricingModels;
import cn.erp.pricing.PricingService;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;

import static cn.erp.quotation.SalesQuoteQueries.*;

@Component
final class SalesQuoteWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PricingService pricing;
    private final MinimumSalePrice minimum;
    private final CrmScope crm;
    SalesQuoteWriter(JdbcTemplate jdbc,ObjectMapper json,PricingService pricing,MinimumSalePrice minimum,CrmScope crm) {
        this.jdbc=jdbc; this.json=json; this.pricing=pricing; this.minimum=minimum; this.crm=crm;
    }
    PricingModels.Preview preview(CompanyAccess actor,SalesQuoteModels.Input input) {
        if(input==null || input.order()==null || input.order().businessDate()==null || input.validTo()==null
                || input.validTo().isBefore(input.order().businessDate()) || input.order().businessDate().isAfter(today()))
            throw invalid("请填写有效报价日期和截止日，报价日期不能晚于今天");
        var order=input.order();
        if(order.deliveryDate()!=null && order.deliveryDate().isBefore(order.businessDate()))throw invalid("交货日期不能早于报价日期");
        opportunity(actor,input.opportunityId(),Long.parseLong(order.customerId()));
        if(order.lines()==null || !actor.allWarehouses() && order.lines().stream().anyMatch(line->line.warehouseId()==null))
            throw invalid("请填写报价明细，并为明细选择当前有权使用的仓库");
        var lines=order.lines().stream().map(line->new PricingModels.PreviewLine(line.skuId(),line.unitId(),line.warehouseId(),line.qty(),line.unitPrice(),
                line.amountInput(),line.lineDiscountAmount(),line.lineDiscountRatio(),line.taxRate())).toList();
        var fees=order.fees()==null?List.<PricingModels.Fee>of():order.fees().stream().map(fee->new PricingModels.Fee(fee.feeType(),fee.bearer(),fee.amount(),fee.taxRate())).toList();
        var permissions=new HashSet<>(actor.permissions());permissions.add("sales_order:create");
        var pricingScope=new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),actor.allWarehouses(),actor.warehouseIds(),permissions,actor.orgScope());
        return pricing.preview(pricingScope,new PricingModels.PreviewRequest("sales_order",order.customerId(),null,order.priceTaxInclusive(),order.orderDiscountAmount(),lines,fees,order.businessDate()));
    }

    void save(CompanyAccess actor,long id,String documentNo,SalesQuoteModels.Input input,PricingModels.Preview preview,boolean create) {
        var normalized=normalized(input.order(),preview);
        if(create)jdbc.update("""
                INSERT INTO sales_quote(id,tenant_id,company_id,document_no,customer_id,opportunity_id,business_date,valid_to,
                  price_tax_inclusive,total_amount,untaxed_amount,tax_amount,order_input,pricing_snapshot,remark,
                  owner_user_id,owner_department_id,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),documentNo,Long.parseLong(normalized.customerId()),input.opportunityId(),normalized.businessDate(),input.validTo(),
                preview.priceTaxInclusive(),preview.totalAmount(),preview.untaxedAmount(),preview.taxAmount(),json.writeValueAsString(normalized),json.writeValueAsString(preview),normalized.remark(),
                actor.userId(),OrgOwnership.department(actor),actor.userId(),actor.userId());
        else {
            jdbc.update("UPDATE sales_quote SET customer_id=?,opportunity_id=?,business_date=?,valid_to=?,price_tax_inclusive=?,total_amount=?,untaxed_amount=?,tax_amount=?,"
                    +"order_input=?,pricing_snapshot=?,remark=?,status='draft',approved_by=NULL,approved_at=NULL,approved_price_fingerprint=NULL,version=version+1,updated_by=?"
                    +" WHERE tenant_id=? AND company_id=? AND id=?",Long.parseLong(normalized.customerId()),input.opportunityId(),normalized.businessDate(),input.validTo(),preview.priceTaxInclusive(),
                    preview.totalAmount(),preview.untaxedAmount(),preview.taxAmount(),json.writeValueAsString(normalized),json.writeValueAsString(preview),normalized.remark(),actor.userId(),actor.tenantId(),actor.companyId(),id);
            jdbc.update("DELETE FROM sales_quote_line WHERE tenant_id=? AND company_id=? AND quote_id=?",actor.tenantId(),actor.companyId(),id);
        }
        var rows=new ArrayList<Object[]>();
        var inserted=new HashSet<Long>();
        for(var line:preview.lines()) {
            long lineId=Ids.next();inserted.add(lineId);
            rows.add(new Object[]{lineId,actor.tenantId(),actor.companyId(),id,line.lineNo(),Long.parseLong(line.skuId()),
                    line.warehouseId()==null?null:Long.parseLong(line.warehouseId()),Long.parseLong(line.unitId()),line.unitRate(),line.qty(),line.baseQty(),line.unitPrice(),line.untaxedAmount(),line.taxAmount()});
        }
        jdbc.batchUpdate("INSERT INTO sales_quote_line(id,tenant_id,company_id,quote_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,untaxed_amount,tax_amount) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",rows);
        var price=minimum.inspectQuotation(actor,id,inserted);
        blocked(price);
        jdbc.update("UPDATE sales_quote SET below_min_price=? WHERE tenant_id=? AND company_id=? AND id=?",price.get("belowMinPrice"),actor.tenantId(),actor.companyId(),id);
    }

    SalesOrderModels.Input conversionInput(CompanyAccess actor,Map<String,Object> head,java.time.LocalDate day) {
        if(day==null || day.isAfter(today()) || day.isBefore(date(head,"business_date")))throw invalid("转单日期不能早于报价日期或晚于今天");
        var frozen=json.readValue(head.get("order_input").toString(),SalesOrderModels.Input.class);
        opportunity(actor,head.get("opportunity_id")==null?null:number(head,"opportunity_id"),number(head,"customer_id"));
        if(frozen.deliveryDate()!=null && frozen.deliveryDate().isBefore(day))throw invalid("报价交货日期已早于转单日期，请先修改报价并重新确认");
        var order=new SalesOrderModels.Input(frozen.customerId(),day,frozen.deliveryDate(),frozen.priceTaxInclusive(),frozen.orderDiscountAmount(),frozen.lines(),frozen.fees(),
                frozen.remark(),null,null,frozen.salesUserId(),frozen.deliveryAreaId(),frozen.deliveryAddress(),frozen.extJson(),frozen.paymentTerms(),frozen.inheritPaymentTerms());
        // 已确认报价使用明确价格及税率，只检查换算和总额是否仍与原报价一致。
        var permissions=new HashSet<>(actor.permissions());permissions.add("document:change_tax_rate");
        var internal=new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),actor.allWarehouses(),actor.warehouseIds(),permissions,actor.orgScope());
        var current=preview(internal,new SalesQuoteModels.Input(order,date(head,"valid_to"),head.get("opportunity_id")==null?null:number(head,"opportunity_id"),null));
        var saved=json.readValue(head.get("pricing_snapshot").toString(),PricingModels.Preview.class);
        if(!current.lines().equals(saved.lines()) || !current.fees().equals(saved.fees()) || new BigDecimal(current.totalAmount()).compareTo(amount(head,"total_amount"))!=0)
            throw new ApiException(409,"QUOTE_TERMS_CHANGED","商品换算或计价依据已变化，请修改报价并重新确认后再转单");
        return order;
    }
    static void blocked(Map<String,Object> price) {
        if(Boolean.TRUE.equals(price.get("belowMinPrice")) && "block".equals(price.get("belowMinPricePolicy")))
            throw new ApiException(422,"MIN_SALE_PRICE_BLOCKED","报价低于当前最低售价，公司规则禁止该报价");
    }
    private SalesOrderModels.Input normalized(SalesOrderModels.Input input,PricingModels.Preview preview) {
        var lines=preview.lines().stream().map(line->new SalesOrderModels.Line(null,line.skuId(),line.warehouseId(),line.unitId(),line.qty(),line.unitPrice(),null,
                line.lineDiscountAmount(),null,line.taxRate())).toList();
        var fees=preview.fees().stream().map(fee->new SalesOrderModels.Fee(null,fee.feeType(),fee.bearer(),fee.amount(),fee.taxRate())).toList();
        return new SalesOrderModels.Input(input.customerId(),input.businessDate(),input.deliveryDate(),preview.priceTaxInclusive(),preview.orderDiscountAmount(),lines,fees,input.remark(),null,null,
                input.salesUserId(),input.deliveryAreaId(),input.deliveryAddress(),input.extJson(),input.paymentTerms(),input.inheritPaymentTerms());
    }
    private void opportunity(CompanyAccess actor,Long id,long customer) {
        if(id==null)return;
        if(!actor.hasPermission("crm_business:view"))throw new ApiException(403,"FORBIDDEN","关联商机需要商机查看权限");
        crm.requireCustomer(actor,customer,false,true);
        if(jdbc.queryForList("SELECT id FROM crm_business WHERE tenant_id=? AND company_id=? AND partner_id=? AND id=? FOR SHARE",
                Long.class,actor.tenantId(),actor.companyId(),customer,id).isEmpty())throw invalid("所选商机不属于当前公司和客户");
    }
}
