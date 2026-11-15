package cn.erp.pricing;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.pricing.PricingDecimals.*;

/** 从当前资料解析默认值，计算结果供单据保存时写入快照。 */
@Service
@Transactional(readOnly=true)
public class PricingService {
    private static final String DEFAULT_FEE_TAX_RATE="0.130000";
    private final JdbcTemplate jdbc;
    private final PricingEngine engine;
    private final CatalogAvailability catalog;
    private final ReferenceAvailability partners;

    public PricingService(JdbcTemplate jdbc,PricingEngine engine,CatalogAvailability catalog,ReferenceAvailability partners) {
        this.jdbc=jdbc;this.engine=engine;this.catalog=catalog;this.partners=partners;
    }

    public PricingModels.Preview preview(CompanyAccess scope,PricingModels.PreviewRequest input) {
        if(input==null || input.documentType()==null || !List.of("sales_order","purchase_order").contains(input.documentType())) {
            throw invalid("单据类型无效");
        }
        boolean sales=input.documentType().equals("sales_order");
        String permission=sales?"sales_order:create":"purchase_order:create";
        if(!scope.hasPermission(permission)) {throw new ApiException(403,"FORBIDDEN","没有该类单据的录入权限");}
        long partner=identifier(sales?input.customerId():input.supplierId(),"往来单位");
        String role=sales?"customer":"supplier";
        partners.requirePartner(scope,partner,role);
        if(input.lines()==null || input.lines().isEmpty() || input.lines().size()>500) {throw invalid("商品明细须在1到500行之间");}
        var partnerDefaults=jdbc.queryForMap("SELECT price_tax_inclusive FROM partner_role WHERE tenant_id=? AND partner_id=? AND role=?",
                scope.tenantId(),partner,role);
        boolean inclusive=input.priceTaxInclusive()!=null?input.priceTaxInclusive():flag(partnerDefaults.get("price_tax_inclusive"));
        var keys=new ArrayList<CatalogAvailability.SkuUnit>();var checkedWarehouses=new java.util.HashSet<String>();
        for(var line:input.lines()) {
            if(line==null) {throw invalid("商品行不能为空");}
            keys.add(new CatalogAvailability.SkuUnit(identifier(line.skuId(),"商品"),identifier(line.unitId(),"单位")));
            if(checkedWarehouses.add(line.warehouseId())) {warehouse(scope,line.warehouseId());}
        }
        var defaults=catalog.requireActiveSkus(scope,keys);
        List<PricingModels.Line> lines=new ArrayList<>();
        for(var line:input.lines()) {lines.add(resolveLine(scope,line,sales,defaults.get(new CatalogAvailability.SkuUnit(Long.parseLong(line.skuId()),Long.parseLong(line.unitId())))));}
        List<PricingModels.Fee> fees=new ArrayList<>();
        if(input.fees()!=null) {
            if(input.fees().size()>50) {throw invalid("费用最多50行");}
            for(var fee:input.fees()) {
                if(fee==null) {throw invalid("费用行不能为空");}
                String tax=chosenTax(scope,fee.taxRate(),DEFAULT_FEE_TAX_RATE);
                fees.add(new PricingModels.Fee(fee.feeType(),fee.bearer(),fee.amount(),tax));
            }
        }
        return engine.calculate(new PricingModels.Calculation(inclusive,input.orderDiscountAmount(),List.copyOf(lines),List.copyOf(fees)));
    }

    private PricingModels.Line resolveLine(CompanyAccess scope,PricingModels.PreviewLine line,boolean sales,Map<String,Object> row) {
        String rate=((BigDecimal)row.get("rate_to_base")).toPlainString();
        String price=line.unitPrice();
        if(price==null && line.amountInput()==null) {
            BigDecimal reference=(BigDecimal)row.get(sales?"sale_price":"purchase_price");
            if(reference==null) {throw invalid("商品未设置参考价格，请填写单价");}
            // 采购参考价本身受字段权限保护，使用参考价亦需该权限。
            if(!sales && !scope.hasPermission("cost:view")) {
                throw new ApiException(403,"FORBIDDEN","读取采购参考价需要查看采购价格权限");
            }
            try {price=reference.multiply(new BigDecimal(rate)).setScale(8,java.math.RoundingMode.UNNECESSARY).toPlainString();}
            catch(ArithmeticException ex) {throw invalid("参考价格换算超过八位小数，请明确录入单价");}
        }
        String tax=chosenTax(scope,line.taxRate(),((BigDecimal)row.get("default_tax_rate")).toPlainString());
        return new PricingModels.Line(line.skuId(),line.unitId(),line.warehouseId(),line.qty(),rate,
                price,line.amountInput(),line.lineDiscountAmount(),line.lineDiscountRatio(),tax);
    }

    private String chosenTax(CompanyAccess scope,String requested,String defaultTax) {
        BigDecimal base=ratio(defaultTax,"默认税率");
        if(requested==null) {return base.toPlainString();}
        BigDecimal selected=ratio(requested,"税率");
        if(selected.compareTo(BigDecimal.ONE)>=0) {throw invalid("税率必须小于1");}
        if(selected.compareTo(base)!=0 && !scope.hasPermission("document:change_tax_rate")) {
            throw new ApiException(422,"TAX_RATE_CHANGE_FORBIDDEN","没有修改税率的权限");
        }
        return selected.toPlainString();
    }

    private void warehouse(CompanyAccess scope,String id) {
        if(id==null) {return;}
        long warehouse=identifier(id,"仓库");
        if(!scope.allowsWarehouse(warehouse)) {throw new ApiException(404,"NOT_FOUND","仓库不存在或无权查看");}
        var rows=jdbc.queryForList("SELECT status FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",
                String.class,scope.tenantId(),scope.companyId(),warehouse);
        if(rows.isEmpty()) {throw new ApiException(404,"NOT_FOUND","仓库不存在或无权查看");}
        if(!rows.getFirst().equals("active")) {throw new ApiException(422,"MASTER_DATA_DISABLED","仓库已停用");}
    }

    private static long identifier(String value,String label) {
        try {
            if(value==null || !value.matches("[1-9][0-9]{0,18}")) {throw invalid("请选择有效的"+label);}
            return Long.parseLong(value);
        } catch(NumberFormatException ex) {throw invalid(label+"编号无效");}
    }

    private static boolean flag(Object value) {
        return value==null || (value instanceof Boolean booleanValue?booleanValue:((Number)value).intValue()!=0);
    }
}
