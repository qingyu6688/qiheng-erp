package cn.erp.gl;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;

import static cn.erp.gl.GlSupport.*;

@Service
@Transactional(readOnly = true)
class GlChoices {
    private final GlSupport s;

    GlChoices(GlSupport s) {
        this.s = s;
    }

    Map<String, Object> choices(CompanyAccess a) {
        s.require(
                a,
                a.hasPermission("gl_config:view")
                        ? "gl_config:view"
                        : a.hasPermission("gl_voucher:view")
                                ? "gl_voucher:view"
                                : "gl_ledger:view");
        var result = new LinkedHashMap<String, Object>();
        for (String role : List.of("customer", "supplier"))
            result.put(
                    role,
                    s
                            .jdbc
                            .queryForList(
                                    "SELECT p.id,p.code,p.name FROM business_partner p JOIN"
                                        + " partner_role r ON r.tenant_id=p.tenant_id AND"
                                        + " r.partner_id=p.id WHERE p.tenant_id=? AND r.role=? AND"
                                        + " r.status='active' AND p.status='active' ORDER BY p.code"
                                        + " LIMIT 3000",
                                    a.tenantId(),
                                    role)
                            .stream()
                            .map(GlSupport::view)
                            .toList());
        result.put(
                "dept",
                s
                        .jdbc
                        .queryForList(
                                "SELECT id,code,name FROM org_department WHERE tenant_id=? AND"
                                    + " company_id=? AND status='active' ORDER BY code LIMIT 3000",
                                a.tenantId(),
                                a.companyId())
                        .stream()
                        .map(GlSupport::view)
                        .toList());
        result.put(
                "employee",
                s
                        .jdbc
                        .queryForList(
                                "SELECT u.id,u.username code,u.display_name name FROM sys_user u"
                                    + " WHERE u.tenant_id=? AND u.status='active' AND EXISTS(SELECT"
                                    + " 1 FROM org_user_data_scope d WHERE d.tenant_id=u.tenant_id"
                                    + " AND d.company_id=? AND d.user_id=u.id) ORDER BY u.id LIMIT"
                                    + " 3000",
                                a.tenantId(),
                                a.companyId())
                        .stream()
                        .map(GlSupport::view)
                        .toList());
        result.put(
                "sku",
                s
                        .jdbc
                        .queryForList(
                                "SELECT id,code,name FROM sku WHERE tenant_id=? AND status='active'"
                                        + " ORDER BY code LIMIT 3000",
                                a.tenantId())
                        .stream()
                        .map(GlSupport::view)
                        .toList());
        for (String type : List.of("project", "custom"))
            result.put(
                    type,
                    s
                            .jdbc
                            .queryForList(
                                    "SELECT i.id,i.code,i.name,i.aux_type_id FROM gl_aux_item i"
                                        + " JOIN gl_aux_type t ON t.tenant_id=i.tenant_id AND"
                                        + " t.company_id=i.company_id AND t.id=i.aux_type_id WHERE"
                                        + " i.tenant_id=? AND i.company_id=? AND t.source=? AND"
                                        + " t.status='active' AND i.status='active' ORDER BY i.code"
                                        + " LIMIT 3000",
                                    a.tenantId(),
                                    a.companyId(),
                                    type)
                            .stream()
                            .map(GlSupport::view)
                            .toList());
        return result;
    }
}
