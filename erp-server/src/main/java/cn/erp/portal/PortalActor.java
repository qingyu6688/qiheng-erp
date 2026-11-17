package cn.erp.portal;

import cn.erp.shared.ApiException;
import jakarta.servlet.http.HttpServletRequest;

/** 门户身份由独立会话认证，在请求内固定公司、往来单位和联系人。 */
public record PortalActor(long tenantId, long companyId, long partnerId, long contactId, long userId,
                          String companyName, String customerName, String contactName) {
    static final String ATTRIBUTE = PortalActor.class.getName();

    public static PortalActor require(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof PortalActor actor) return actor;
        throw expired();
    }

    static ApiException expired() {
        return new ApiException(401, "PORTAL_LOGIN_REQUIRED", "请登录客户门户后重试");
    }
}
