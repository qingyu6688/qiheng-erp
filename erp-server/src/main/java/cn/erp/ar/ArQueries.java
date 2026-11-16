package cn.erp.ar;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

/** 往来页面资料与未清项查询保留各自的权限范围。 */
@Service
@Transactional(readOnly = true)
class ArQueries {
    private final ArSupport s;

    ArQueries(ArSupport s) {
        this.s = s;
    }

    public List<Map<String, Object>> badDebtItems(CompanyAccess a) {
        s.require(a, "bad_debt:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT i.*,b.name partner_name FROM partner_open_item i JOIN"
                                + " business_partner b ON b.tenant_id=i.tenant_id AND"
                                + " b.id=i.partner_id WHERE i.tenant_id=? AND i.company_id=?"
                                + " AND i.item_type='receivable' AND i.direction='D' AND"
                                + " i.open_amount>0 ORDER BY i.aging_date,i.id LIMIT 1000",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(ApiRows::row)
                .toList();
    }

    public Map<String, Object> metadata(CompanyAccess a) {
        if (a.permissions().stream()
                .noneMatch(
                        p ->
                                Set.of("credit_limit:view", "collection:view", "bad_debt:view")
                                        .contains(p)))
            throw new ApiException(403, "FORBIDDEN", "没有查看往来功能的权限");
        return Map.of(
                "customers",
                s
                        .jdbc
                        .queryForList(
                                "SELECT p.id,p.code,p.name FROM business_partner p JOIN"
                                        + " partner_role r ON r.tenant_id=p.tenant_id AND"
                                        + " r.partner_id=p.id WHERE p.tenant_id=? AND"
                                        + " r.role='customer' AND r.status='active' AND"
                                        + " p.status='active' ORDER BY p.code LIMIT 3000",
                                a.tenantId())
                        .stream()
                        .map(ApiRows::row)
                        .toList(),
                "users",
                s
                        .jdbc
                        .queryForList(
                                "SELECT u.id,u.display_name name FROM sys_user u WHERE"
                                        + " u.tenant_id=? AND u.status='active' AND"
                                        + " EXISTS(SELECT 1 FROM sys_user_data_scope d WHERE"
                                        + " d.tenant_id=u.tenant_id AND d.user_id=u.id AND"
                                        + " d.company_id=?) ORDER BY u.id LIMIT 3000",
                                a.tenantId(),
                                a.companyId())
                        .stream()
                        .map(ApiRows::row)
                        .toList());
    }
}
