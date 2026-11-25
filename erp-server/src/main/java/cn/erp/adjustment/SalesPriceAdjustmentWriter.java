package cn.erp.adjustment;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import static cn.erp.adjustment.SalesPriceAdjustmentQueries.*;

@Component
class SalesPriceAdjustmentWriter {
    private final JdbcTemplate jdbc;
    private final SalesPriceAdjustmentQueries queries;
    private final ReferenceAvailability references;
    SalesPriceAdjustmentWriter(JdbcTemplate jdbc,SalesPriceAdjustmentQueries queries,ReferenceAvailability references){this.jdbc=jdbc;this.queries=queries;this.references=references;}
    void validate(CompanyAccess actor,SalesPriceAdjustmentModels.Input input) {
        if(input==null)throw invalid("请填写销售调价内容");PriceAdjustmentQueries.day(input.businessDate());
        if(input.reason()==null||input.reason().isBlank()||input.reason().length()>500)throw invalid("请填写调价原因，最多500字");
        if(input.lines()==null||input.lines().isEmpty()||input.lines().size()>100||input.lines().stream().anyMatch(java.util.Objects::isNull))throw invalid("销售调价明细须为1至100行");
        if(!actor.allowsWarehouse(input.warehouseId()))throw missing();references.requirePartner(actor,input.partnerId(),"customer");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",Integer.class,actor.tenantId(),actor.companyId(),input.warehouseId())!=1)throw invalid("请选择当前公司的启用仓库");
    }
    void save(CompanyAccess actor,long header,SalesPriceAdjustmentModels.Input input) {
        var ids=new HashSet<Long>();for(var line:input.lines())if(line.shipmentLineId()<=0||!ids.add(line.shipmentLineId()))throw invalid("原发货行无效或重复");
        var sources=new LinkedHashMap<Long,Map<String,Object>>();
        for(long id:ids.stream().sorted().toList()){var source=queries.source(actor,id,true);queries.eligible(actor,source,input.partnerId(),input.warehouseId(),input.businessDate());sources.put(id,source);}
        jdbc.update("DELETE FROM price_adjustment_line WHERE tenant_id=? AND company_id=? AND adjustment_id=?",actor.tenantId(),actor.companyId(),header);
        BigDecimal gross=BigDecimal.ZERO,tax=BigDecimal.ZERO,magnitude=BigDecimal.ZERO;int index=0;
        for(var line:input.lines()) {
            var source=sources.get(line.shipmentLineId());var result=PriceAdjustmentMath.sales(remaining(source),amount(source,"unit_rate"),gross(source),tax(source),amount(source,"tax_rate"),line.newUnitPrice(),line.adjustmentAmount());
            magnitude=magnitude.add(result.amount().abs());if(magnitude.setScale(2).precision()>24)throw invalid("调价金额合计超过范围，请拆分单据");
            jdbc.update("""
                    INSERT INTO price_adjustment_line(id,tenant_id,company_id,adjustment_id,line_no,shipment_line_id,source_version,sku_id,unit_id,
                      source_qty,source_base_qty,source_returned_qty,input_unit_price,input_amount,old_unit_price,new_unit_price,old_amount,old_tax,
                      tax_rate,amount,untaxed_amount,tax_amount,cost_tax_inclusive,prior_return_basis) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?)
                    """,Ids.next(),actor.tenantId(),actor.companyId(),header,++index,line.shipmentLineId(),source.get("version"),source.get("sku_id"),source.get("unit_id"),
                    result.quantity(),remaining(source),source.get("returned_base_qty"),line.newUnitPrice()==null?null:new BigDecimal(line.newUnitPrice()),
                    line.adjustmentAmount()==null?null:new BigDecimal(line.adjustmentAmount()),result.oldUnitPrice(),result.newUnitPrice(),gross(source),tax(source),source.get("tax_rate"),
                    result.amount(),result.untaxed(),result.tax(),source.get("pricing_return_basis"));
            gross=gross.add(result.amount());tax=tax.add(result.tax());
        }
        jdbc.update("UPDATE price_adjustment SET amount=?,tax_amount=?,untaxed_amount=? WHERE tenant_id=? AND company_id=? AND id=?",gross,tax,gross.subtract(tax),actor.tenantId(),actor.companyId(),header);
    }
    Map<Long,Map<String,Object>> revalidate(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines) {
        var result=new LinkedHashMap<Long,Map<String,Object>>();
        for(var line:lines.stream().sorted(java.util.Comparator.comparingLong(row->number(row,"shipment_line_id"))).toList()) {
            var source=queries.source(actor,number(line,"shipment_line_id"),true);
            queries.eligible(actor,source,number(header,"partner_id"),number(header,"warehouse_id"),date(header,"business_date"));
            if(number(source,"version")!=number(line,"source_version"))throw new ApiException(409,"PRICE_SOURCE_CHANGED","原发货已有退货、调价或其他修改，请更新草稿；已批准单请作废后重新申请");
            result.put(number(line,"id"),source);
        }
        return result;
    }
}
