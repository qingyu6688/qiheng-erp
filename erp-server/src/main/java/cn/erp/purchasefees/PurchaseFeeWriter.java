package cn.erp.purchasefees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import static cn.erp.purchasefees.PurchaseFeeQueries.*;

@Component
class PurchaseFeeWriter {
    private final JdbcTemplate jdbc;
    private final PurchaseFeeQueries queries;
    private final ReferenceAvailability references;
    PurchaseFeeWriter(JdbcTemplate jdbc,PurchaseFeeQueries queries,ReferenceAvailability references) {
        this.jdbc=jdbc;this.queries=queries;this.references=references;
    }
    boolean validate(CompanyAccess actor,PurchaseFeeModels.Input input) {
        if(input==null) throw invalid("请填写费用单内容");
        day(input.businessDate());
        if(input.reason()==null||input.reason().isBlank()||input.reason().length()>500) throw invalid("请填写费用原因，最多500字");
        if(!Set.of("amount","quantity","weight").contains(Objects.toString(input.allocationMethod(),""))) throw invalid("请选择金额、数量或重量分摊");
        if(input.fees()==null||input.fees().isEmpty()||input.fees().size()>50||input.fees().stream().anyMatch(Objects::isNull)) throw invalid("费用明细须为1到50行");
        if(input.receiptLineIds()==null||input.receiptLineIds().isEmpty()||input.receiptLineIds().size()>100||input.receiptLineIds().stream().anyMatch(id->id==null||id<=0)) throw invalid("请选择1到100个收货来源");
        if(new HashSet<>(input.receiptLineIds()).size()!=input.receiptLineIds().size()) throw invalid("同一收货来源不能重复选择");
        if(!actor.hasPermission("partner:view")) throw new ApiException(403,"FORBIDDEN","选择费用承运商需要往来单位查看权限");
        references.requirePartner(actor,input.partnerId(),"supplier");
        input.fees().forEach(PurchaseFeeWriter::calculate);
        return queries.requireEnabled(actor);
    }
    long primaryWarehouse(CompanyAccess actor,PurchaseFeeModels.Input input) {
        return number(queries.source(actor,input.receiptLineIds().stream().min(Long::compareTo).orElseThrow(),true),"warehouse_id");
    }
    void save(CompanyAccess actor,long id,PurchaseFeeModels.Input input,boolean inclusive) {
        var sources=new LinkedHashMap<Long,Map<String,Object>>();
        for(long sourceId:input.receiptLineIds().stream().sorted().toList()) {
            var source=queries.source(actor,sourceId,true);
            queries.eligible(actor,source,input.businessDate());sources.put(sourceId,source);
        }
        jdbc.update("DELETE FROM purchase_fee_allocation WHERE tenant_id=? AND company_id=? AND document_id=?",actor.tenantId(),actor.companyId(),id);
        jdbc.update("DELETE FROM purchase_fee_doc_line WHERE tenant_id=? AND company_id=? AND document_id=?",actor.tenantId(),actor.companyId(),id);
        BigDecimal gross=BigDecimal.ZERO,tax=BigDecimal.ZERO;
        int lineNo=0;
        for(var inputLine:input.fees()) {
            var fee=calculate(inputLine);gross=gross.add(fee.amount());tax=tax.add(fee.tax());
            checkMoney(gross);
            jdbc.update("INSERT INTO purchase_fee_doc_line(id,tenant_id,company_id,document_id,line_no,fee_type,amount,untaxed_amount,tax_amount,tax_rate,remark) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    Ids.next(),actor.tenantId(),actor.companyId(),id,++lineNo,inputLine.feeType(),fee.amount(),fee.amount().subtract(fee.tax()),fee.tax(),fee.rate(),inputLine.remark());
        }
        var weights=sources.entrySet().stream().map(entry->new PurchaseFeeMath.Weight(entry.getKey(),weight(entry.getValue(),input.allocationMethod()))).toList();
        for(var part:PurchaseFeeMath.allocate(gross,tax,weights)) {
            var source=sources.get(part.sourceId());
            insertAllocation(actor,id,source,weight(source,input.allocationMethod()),part.amount(),part.tax(),inclusive);
        }
        jdbc.update("UPDATE purchase_fee_doc SET amount=?,tax_amount=?,untaxed_amount=? WHERE tenant_id=? AND company_id=? AND id=?",gross,tax,gross.subtract(tax),actor.tenantId(),actor.companyId(),id);
    }
    void insertAllocation(CompanyAccess actor,long id,Map<String,Object> source,BigDecimal weight,BigDecimal gross,BigDecimal tax,boolean inclusive) {
        jdbc.update("""
                INSERT INTO purchase_fee_allocation(id,tenant_id,company_id,document_id,receipt_line_id,source_version,sku_id,unit_id,
                  source_base_qty,source_returned_qty,source_amount,source_tax,sku_weight_kg,basis_weight,amount,untaxed_amount,tax_amount,cost_amount)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,Ids.next(),actor.tenantId(),actor.companyId(),id,source.get("id"),source.get("version"),source.get("sku_id"),source.get("unit_id"),
                remaining(source),source.get("returned_base_qty"),remainingAmount(source),remainingTax(source),source.get("sku_weight_kg"),weight,
                gross,gross.subtract(tax),tax,inclusive?gross:gross.subtract(tax));
    }
    void revalidate(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> allocations) {
        boolean inclusive=queries.requireEnabled(actor);
        references.requirePartner(actor,number(header,"partner_id"),"supplier");
        if(inclusive!=flag(header,"cost_tax_inclusive")) throw new ApiException(409,"FEE_POLICY_CHANGED","公司纳税人身份已变化，请更新草稿；已审批费用请作废后重新申请");
        for(var line:allocations.stream().sorted(Comparator.comparingLong(row->number(row,"receipt_line_id"))).toList()) {
            var source=queries.source(actor,number(line,"receipt_line_id"),true);
            queries.eligible(actor,source,date(header,"business_date"));
            if(number(source,"version")!=number(line,"source_version")||remaining(source).compareTo(amount(line,"source_base_qty"))!=0
                    ||amount(source,"returned_base_qty").compareTo(amount(line,"source_returned_qty"))!=0
                    ||remainingAmount(source).compareTo(amount(line,"source_amount"))!=0||remainingTax(source).compareTo(amount(line,"source_tax"))!=0
                    ||"weight".equals(header.get("allocation_method"))&&amount(source,"sku_weight_kg").compareTo(amount(line,"sku_weight_kg"))!=0)
                throw new ApiException(409,"FEE_SOURCE_CHANGED","来源收货、退货、调价或重量已变化，请更新草稿；已审批费用请作废后重新申请");
        }
    }
    static BigDecimal weight(Map<String,Object> source,String method) {
        return switch(method) {
            case "amount" -> remainingAmount(source).subtract(remainingTax(source));
            case "quantity" -> remaining(source);
            case "weight" -> remaining(source).multiply(amount(source,"sku_weight_kg"));
            default -> throw invalid("分摊方式无效");
        };
    }
    record Fee(BigDecimal amount,BigDecimal tax,BigDecimal rate) {}
    static Fee calculate(PurchaseFeeModels.FeeInput input) {
        if(input==null||!Set.of("freight","loading","other").contains(Objects.toString(input.feeType(),""))) throw invalid("费用类型须为运费、装卸费或其他费用");
        if(input.remark()!=null&&input.remark().length()>200) throw invalid("费用备注最多200字");
        try {
            if(input.amount()==null||!input.amount().matches("[0-9]{1,22}(?:\\.[0-9]{1,2})?")
                    ||input.taxRate()==null||!input.taxRate().matches("0(?:\\.[0-9]{1,6})?")) throw invalid("费用金额或税率格式无效");
            var gross=new BigDecimal(input.amount());var rate=new BigDecimal(input.taxRate());
            if(gross.signum()<=0||gross.scale()>2||rate.signum()<0||rate.compareTo(BigDecimal.ONE)>=0||rate.scale()>6) throw invalid("费用须为正数且最多两位小数，税率须在0到1之间且最多六位小数");
            gross=gross.setScale(2);checkMoney(gross);
            var untaxed=gross.divide(BigDecimal.ONE.add(rate),2,RoundingMode.HALF_UP);
            return new Fee(gross,gross.subtract(untaxed),rate);
        } catch(NumberFormatException error) {throw invalid("费用金额或税率格式无效");}
    }
    private static void checkMoney(BigDecimal value) {if(value.setScale(2).precision()>24) throw invalid("费用金额合计超过允许范围，请拆分费用单");}
}
