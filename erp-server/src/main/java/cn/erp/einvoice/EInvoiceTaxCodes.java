package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EInvoiceTaxCodes {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    EInvoiceTaxCodes(JdbcTemplate jdbc,AuditService audit) {this.jdbc=jdbc;this.audit=audit;}

    record Input(@Pattern(regexp="[0-9]{6,24}",message="税收分类编码须为6至24位数字") String taxCategoryCode,
                 @NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}

    List<Map<String,Object>> list(CompanyAccess actor,String keyword) {
        require(actor,"sku:view");
        String query=keyword==null?"":keyword.strip();
        if(query.length()>100)throw EInvoiceConfig.invalid("商品检索条件最多一百字");
        return jdbc.queryForList("""
                SELECT k.id,k.code,k.name,k.tax_category_code,k.version FROM sku k
                WHERE k.tenant_id=? AND (INSTR(k.code,?)>0 OR INSTR(k.name,?)>0 OR EXISTS(
                    SELECT 1 FROM sku_barcode b WHERE b.tenant_id=k.tenant_id AND b.sku_id=k.id AND INSTR(b.barcode,?)>0))
                ORDER BY k.id DESC LIMIT 100
                """,actor.tenantId(),query,query,query).stream().map(ApiRows::row).toList();
    }

    @Transactional
    Map<String,Object> save(CompanyAccess actor,long id,Input input) {
        require(actor,"sku:update");
        EInvoiceConfig.requireFull(actor);
        var before=jdbc.queryForList("SELECT id,code,name,tax_category_code,version FROM sku WHERE tenant_id=? AND id=? FOR UPDATE",
                actor.tenantId(),id).stream().findFirst().orElseThrow(EInvoiceSources::missing);
        if(EInvoiceSources.number(before,"version")!=input.version())
            throw new ApiException(409,"VERSION_CONFLICT","商品已更新，请刷新后重试");
        jdbc.update("UPDATE sku SET tax_category_code=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                input.taxCategoryCode(),actor.userId(),actor.tenantId(),id);
        var after=jdbc.queryForMap("SELECT id,code,name,tax_category_code,version FROM sku WHERE tenant_id=? AND id=?",actor.tenantId(),id);
        audit.record(actor,"einv.sku_tax_category","sku",id,ApiRows.row(before),ApiRows.row(after));
        return ApiRows.row(after);
    }

    private void require(CompanyAccess actor,String permission) {
        if(!actor.hasPermission(permission))throw new ApiException(403,"FORBIDDEN","没有维护税收分类编码所需的商品权限");
    }
}
