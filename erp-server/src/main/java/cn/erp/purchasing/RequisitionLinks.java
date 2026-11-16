package cn.erp.purchasing;
import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.PricingModels;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static cn.erp.purchasing.PurchaseOrderQueries.*;

/** 草稿转单即占用请购额度，修改、删除或取消时在同一事务释放差量。 */
@Service
class RequisitionLinks {
    private final JdbcTemplate jdbc;
    RequisitionLinks(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    void replace(CompanyAccess scope,long order,PurchaseOrderModels.Input input,PricingModels.Preview preview) {
        var old=jdbc.queryForList("SELECT requisition_line_id,base_qty FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0 AND requisition_line_id IS NOT NULL",scope.tenantId(),scope.companyId(),order);
        var previous=new TreeMap<Long,BigDecimal>();old.forEach(row->previous.merge(number(row,"requisition_line_id"),decimal(row,"base_qty"),BigDecimal::add));
        var selected=new TreeMap<Long,BigDecimal>();var skus=new HashMap<Long,Long>();
        for(int i=0;i<input.lines().size();i++) {
            String source=input.lines().get(i).requisitionLineId();if(source==null) {continue;}
            long id=identifier(source),sku=identifier(input.lines().get(i).skuId());
            if(skus.putIfAbsent(id,sku)!=null&&skus.get(id)!=sku) {throw invalid("同一请购行不能引用不同商品");}
            selected.merge(id,new BigDecimal(preview.lines().get(i).baseQty()),BigDecimal::add);
        }
        adjust(scope,previous,selected,skus);
    }
    void release(CompanyAccess scope,long order) {
        var old=jdbc.queryForList("SELECT requisition_line_id,base_qty FROM purchase_order_line WHERE tenant_id=? AND company_id=? AND order_id=? AND is_deleted=0 AND requisition_line_id IS NOT NULL",scope.tenantId(),scope.companyId(),order);
        var previous=new TreeMap<Long,BigDecimal>();old.forEach(row->previous.merge(number(row,"requisition_line_id"),decimal(row,"base_qty"),BigDecimal::add));
        adjust(scope,previous,Map.of(),Map.of());
    }
    private void adjust(CompanyAccess scope,Map<Long,BigDecimal> previous,Map<Long,BigDecimal> selected,Map<Long,Long> skus) {
        var ids=new TreeSet<Long>();ids.addAll(previous.keySet());ids.addAll(selected.keySet());
        var heads=new TreeSet<Long>();
        for(long id:ids) {var rows=jdbc.queryForList("SELECT requisition_id FROM purchase_requisition_line WHERE tenant_id=? AND company_id=? AND id=?",Long.class,scope.tenantId(),scope.companyId(),id);if(rows.isEmpty()) {throw missing();}heads.add(rows.getFirst());}
        for(long head:heads) {
            cn.erp.shared.OrgOwnership.require(jdbc,scope,"purchase_requisition",head);
            jdbc.queryForObject("SELECT id FROM purchase_requisition WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId(),head);
        }
        for(long id:ids) {
            var row=jdbc.queryForMap("SELECT l.*,h.status,h.is_deleted FROM purchase_requisition_line l JOIN purchase_requisition h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.requisition_id WHERE l.tenant_id=? AND l.company_id=? AND l.id=? FOR UPDATE",scope.tenantId(),scope.companyId(),id);
            BigDecimal before=previous.getOrDefault(id,BigDecimal.ZERO),after=selected.getOrDefault(id,BigDecimal.ZERO),delta=after.subtract(before);
            if(after.signum()>0) {
                if(number(row,"sku_id")!=skus.get(id)||(!"approved".equals(row.get("status"))&&!("closed".equals(row.get("status"))&&after.compareTo(before)<=0))||flag(row.get("is_deleted"))) {throw new ApiException(422,"REQUISITION_NOT_APPROVED","来源请购须已批准且商品一致");}
            }
            if(delta.signum()!=0&&jdbc.update("UPDATE purchase_requisition_line SET ordered_base_qty=ordered_base_qty+?,version=version+1,updated_by=? WHERE id=? AND ordered_base_qty+?>=0 AND ordered_base_qty+?<=base_qty",delta,scope.userId(),id,delta,delta)!=1) {throw new ApiException(422,"REQUISITION_QTY_EXCEEDED","转采购数量超过请购剩余数量");}
        }
    }
}
