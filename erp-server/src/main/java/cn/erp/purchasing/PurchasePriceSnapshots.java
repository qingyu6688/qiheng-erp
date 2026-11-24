package cn.erp.purchasing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.PricingModels;
import cn.erp.pricing.PricingService;
import cn.erp.shared.ApiException;

import static cn.erp.purchasing.PurchaseOrderQueries.*;

/** 订单保存时冻结取价依据，提交与审批比较协议版本，避免绕过超价审批。 */
@Component
final class PurchasePriceSnapshots {
    private final JdbcTemplate jdbc;
    private final PricingService pricing;
    private final ObjectMapper json;
    PurchasePriceSnapshots(JdbcTemplate jdbc,PricingService pricing,ObjectMapper json) { this.jdbc=jdbc; this.pricing=pricing; this.json=json; }

    void line(CompanyAccess actor,long line,PricingModels.PriceSource source) {
        jdbc.update("UPDATE purchase_order_line SET price_source_snapshot=? WHERE tenant_id=? AND company_id=? AND id=?",
                json.writeValueAsString(source),actor.tenantId(),actor.companyId(),line);
    }
    void header(CompanyAccess actor,long order,List<PricingModels.PriceSource> sources) {
        boolean above=sources.stream().anyMatch(PricingModels.PriceSource::abovePriceList);
        var stored=jdbc.queryForList("SELECT price_source_snapshot FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0 ORDER BY id",
                String.class,actor.tenantId(),actor.companyId(),order).stream().map(value->json.readValue(value,PricingModels.PriceSource.class)).toList();
        jdbc.update("UPDATE purchase_order SET above_price_list=?,purchase_price_approval_required=?,purchase_price_hash=? WHERE tenant_id=? AND company_id=? AND id=?",
                above,above && approvalPolicy(actor),hash(stored),actor.tenantId(),actor.companyId(),order);
    }
    boolean required(CompanyAccess actor,Map<String,Object> head) {
        return flag(head.get("above_price_list")) && (flag(head.get("purchase_price_approval_required")) || approvalPolicy(actor));
    }
    boolean required(CompanyAccess actor,PricingModels.Preview preview) {
        return approvalPolicy(actor) && preview.priceSources().stream().anyMatch(PricingModels.PriceSource::abovePriceList);
    }
    void validate(CompanyAccess actor,Map<String,Object> head,List<Map<String,Object>> lines,boolean approval) {
        var permissions=new HashSet<>(actor.permissions());
        permissions.addAll(List.of("purchase_order:create","document:change_tax_rate"));
        var internal=new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),actor.allWarehouses(),actor.warehouseIds(),permissions,actor.orgScope());
        var inputs=lines.stream().map(line->new PricingModels.PreviewLine(line.get("sku_id").toString(),line.get("unit_id").toString(),
                line.get("warehouse_id")==null?null:line.get("warehouse_id").toString(),decimal(line,"qty").toPlainString(),decimal(line,"unit_price").toPlainString(),
                null,null,null,decimal(line,"tax_rate").toPlainString())).toList();
        var current=pricing.purchaseDocumentPreview(internal,new PricingModels.PreviewRequest("purchase_order",null,head.get("supplier_id").toString(),
                flag(head.get("price_tax_inclusive")),"0",inputs,List.of(),((java.sql.Date)head.get("business_date")).toLocalDate())).priceSources();
        // 来源字段描述价格取值，提交时的显式价格不应让“默认取价”快照变成另一份摘要。
        String next=hash(current);
        if(head.get("purchase_price_hash")==null) {
            for(int index=0;index<lines.size();index++)line(actor,number(lines.get(index),"id"),current.get(index));
            header(actor,number(head,"id"),current);
            head.put("above_price_list",current.stream().anyMatch(PricingModels.PriceSource::abovePriceList));
        } else if(!next.equals(head.get("purchase_price_hash"))) {
            throw new ApiException(409,"PURCHASE_PRICE_SOURCE_CHANGED","采购协议或取价依据已变化，请退回草稿重新保存后提交");
        }
        if(approval && required(actor,head)) {
            if(!actor.hasPermission("cost:view") || number(head,"created_by")==actor.userId())
                throw new ApiException(422,"PURCHASE_PRICE_REVIEW_REQUIRED","超出协议价须由另一名具有成本查看权限的采购审批人复核");
            if(number(head,"wf_instance_id")==0)
                throw new ApiException(422,"PURCHASE_PRICE_REVIEW_REQUIRED","超出协议价须撤回后通过人工工作流重新提交");
        }
    }

    private boolean approvalPolicy(CompanyAccess actor) {
        var policies=jdbc.queryForList("SELECT purchase_over_price_policy FROM company_policy WHERE tenant_id=? AND company_id=?",String.class,actor.tenantId(),actor.companyId());
        return !policies.isEmpty() && policies.getFirst().equals("approval");
    }
    private String hash(List<PricingModels.PriceSource> sources) {
        // 历史价和参考价只是保存时的建议，不因后来多了一张采购单就推翻已经录入的价格。
        var references=sources.stream().map(source->source.referenceSource().equals("supplier_price_list")
                ?new PricingModels.PriceSource(0,"snapshot","supplier_price_list",source.suggestedUnitPrice(),source.priceListId(),source.priceListVersion(),null,source.abovePriceList())
                :new PricingModels.PriceSource(0,"snapshot","frozen",null,null,null,null,false)).toList();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(references).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException("当前运行环境缺少SHA-256算法",error); }
    }
    private static boolean flag(Object value) { return value instanceof Boolean flag?flag:value instanceof Number number && number.intValue()!=0; }
}
