package cn.erp.ar;

import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.ar.ArSupport.*;

@Component
class CreditWorkflow implements WorkflowBusiness {
    private final CreditAdministration service;

    CreditWorkflow(CreditAdministration service) {
        this.service = service;
    }

    public String businessType() {
        return "credit_increase";
    }

    public Map<String, Object> snapshot(ActorScope scope, long id) {
        var a = (CompanyAccess) scope;
        var raw = service.increase(a, id);
        var result = new LinkedHashMap<>(raw);
        result.put("documentNo", "CL-" + id);
        result.put("title", "临时信用额度调增 CL-" + id);
        result.put("totalAmount", raw.get("amount"));
        result.put("customerId", raw.get("partnerId"));
        result.put("ownerDeptId", Objects.toString(raw.get("ownerDepartmentId"), "0"));
        return result;
    }

    public void decide(ActorScope scope, long id, String outcome, String comment, String key) {
        if (!WorkflowContext.allows(scope, businessType(), id))
            throw new ApiException(403, "FORBIDDEN", "缺少有效信用审批上下文");
        var a = (CompanyAccess) scope;
        var row = service.increase(a, id);
        service.changeIncrease(
                a,
                id,
                outcome.equals("approved") ? "approve" : "reject",
                key,
                new CreditAdministration.Command(number(row.get("version")), comment, null));
    }
}
