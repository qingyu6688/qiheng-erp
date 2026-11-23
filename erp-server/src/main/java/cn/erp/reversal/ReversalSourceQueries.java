package cn.erp.reversal;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ManufacturingReversalPort;
import cn.erp.shared.ManufacturingScope;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.salesfinance.FinanceSupport.*;

/** 申请冲销时按单号选来源，只提供业务识别信息。 */
@Service
@Transactional(readOnly=true)
class ReversalSourceQueries {
    private static final Map<String,String> NUMBERS=Map.ofEntries(Map.entry("shipment","shipment_no"),Map.entry("sales_return","return_no"),
        Map.entry("goods_receipt","receipt_no"),Map.entry("purchase_return","return_no"),Map.entry("receipt","receipt_no"),
        Map.entry("payment","payment_no"),Map.entry("account_transfer","transfer_no"),Map.entry("stock_adjustment","adjust_no"),
        Map.entry("stock_misc_doc","document_no"),Map.entry("mfg_issue_doc","document_no"),Map.entry("mfg_receipt_doc","document_no"),Map.entry("price_adjustment","adjust_no"));
    private final JdbcTemplate jdbc;
    ReversalSourceQueries(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    Map<String,Object> list(CompanyAccess scope,String sourceType,Long cursor,int limit,String keyword) {
        long before=cursor(cursor,limit);
        if(!NUMBERS.containsKey(sourceType)||keyword.length()>100) {throw invalid("来源类型或单号筛选无效");}
        if(!scope.hasPermission(ManufacturingReversalPort.viewPermission(sourceType))) {throw new cn.erp.shared.ApiException(403,"FORBIDDEN","没有查看来源单据的权限");}
        boolean warehouse=Set.of("shipment","sales_return","goods_receipt","purchase_return","stock_adjustment","stock_misc_doc").contains(sourceType);
        if(warehouse&&!scope.allWarehouses()&&scope.warehouseIds().isEmpty()) {return page(List.of(),limit);}
        String table=NUMBERS.keySet().stream().filter(sourceType::equals).findFirst().orElseThrow();
        String number=NUMBERS.get(table),states=sourceType.equals("shipment")?"'posted','signed','signed_with_diff'":Set.of("receipt","payment","account_transfer").contains(sourceType)?"'confirmed'":"'posted'";
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),before,keyword));
        String filter=cn.erp.shared.OrgOwnership.filter(scope,"d");
        if(sourceType.equals("price_adjustment"))filter+=" AND "+cn.erp.shared.PriceAdjustmentScope.condition(scope,"d");
        if(ManufacturingReversalPort.supports(sourceType)) {filter+=" AND "+ManufacturingScope.stockCondition(scope,"d");}
        if(warehouse&&!scope.allWarehouses()) {filter+=" AND d.warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";args.addAll(scope.warehouseIds());}
        args.add(sourceType);
        args.add(limit+1);
        return page(jdbc.queryForList("SELECT d.id,d."+number+" document_no,d.business_date,d.status FROM "+table+" d WHERE d.tenant_id=? AND d.company_id=? AND d.id<? AND LOCATE(?,d."+number+")>0 AND d.status IN ("+states+")"+filter+
            " AND NOT EXISTS(SELECT 1 FROM document_reversal r WHERE r.tenant_id=d.tenant_id AND r.company_id=d.company_id AND r.source_type=? AND r.source_id=d.id AND r.status<>'rejected') ORDER BY d.id DESC LIMIT ?",args.toArray()),limit);
    }
}
