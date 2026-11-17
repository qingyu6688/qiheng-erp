package cn.erp.reference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新单据引用基础资料时使用，调用方与单据写入共用事务。 */
@Service
public class ReferenceAvailability {
    private final JdbcTemplate jdbc;
    public ReferenceAvailability(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    @Transactional(readOnly=true)
    public void requirePartner(CompanyAccess scope,long id,String role) {
        validatePartner(scope,id,role,true);
    }

    @Transactional(readOnly=true,noRollbackFor=ApiException.class)
    public void checkPartner(CompanyAccess scope,long id,String role) {
        validatePartner(scope,id,role,false);
    }

    private void validatePartner(CompanyAccess scope,long id,String role,boolean lock) {
        if (!Set.of("customer","supplier").contains(role)) {throw ReferenceRows.invalid("往来单位角色无效");}
        var rows=jdbc.queryForList("""
                SELECT p.status,r.status AS role_status FROM business_partner p
                JOIN partner_role r ON r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role=?
                WHERE p.tenant_id=? AND p.id=?
                """+(lock?" FOR SHARE":""),role,scope.tenantId(),id);
        if (rows.isEmpty()) {throw new ApiException(404,"NOT_FOUND","往来单位或业务角色不存在");}
        if(!"active".equals(rows.getFirst().get("status")) || !"active".equals(rows.getFirst().get("role_status"))) {
            throw new ApiException(422,"MASTER_DATA_DISABLED","往来单位或业务角色已停用");
        }
    }

    @Transactional(readOnly=true)
    public void requireCashAccount(CompanyAccess scope,long id) {
        var status=jdbc.queryForList("SELECT status FROM cash_account WHERE tenant_id=? AND company_id=? AND id=? FOR SHARE",
                String.class,scope.tenantId(),scope.companyId(),id);
        if(status.isEmpty()) {throw new ApiException(404,"NOT_FOUND","结算账户不存在或无权查看");}
        if(!"active".equals(status.getFirst())) {throw new ApiException(422,"MASTER_DATA_DISABLED","结算账户已停用");}
    }
}
