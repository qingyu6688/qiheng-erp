package cn.erp.reference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** CRM 复用的主数据写入入口，客户画像的范围由 CRM 调用方校验。 */
@Service
public class CrmPartnerMaster {
    private final PartnerService partners;
    private final JdbcTemplate jdbc;

    CrmPartnerMaster(PartnerService partners, JdbcTemplate jdbc) {
        this.partners = partners;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long createCustomer(CompanyAccess actor, String code, String name, String taxNo, Long areaId, String source) {
        var saved = partners.create(actor, new PartnerModels.Partner(code, name, null, taxNo, Set.of("customer"), null, areaId, source));
        return Long.parseLong(saved.get("id").toString());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureCustomer(CompanyAccess actor, long partnerId, boolean allowNewRole) {
        var master = partners.get(actor, partnerId, true);
        if (!"active".equals(master.get("status"))) throw disabled();
        var roles = jdbc.queryForList("SELECT status FROM partner_role WHERE tenant_id=? AND partner_id=? AND role='customer'",
                String.class, actor.tenantId(), partnerId);
        if (!roles.isEmpty()) {
            if (!"active".equals(roles.getFirst())) throw disabled();
            return;
        }
        if (!allowNewRole) throw new ApiException(422, "CRM_CUSTOMER_ROLE_REQUIRED", "此往来单位尚未启用客户角色");
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,created_by,updated_by) VALUES(?,?,?,'customer',?,?)",
                Ids.next(), actor.tenantId(), partnerId, actor.userId(), actor.userId());
    }

    public Map<String, Object> saveContact(CompanyAccess actor, long partnerId, Long id, String name, String mobile,
                                           String email, String address, boolean isDefault, Long version, Long areaId) {
        return partners.saveContact(actor, partnerId, id, new PartnerModels.Contact(name, mobile, email, address, isDefault, version, areaId));
    }

    private ApiException disabled() {
        return new ApiException(409, "CRM_CUSTOMER_DISABLED", "客户或其往来角色已停用");
    }
}
