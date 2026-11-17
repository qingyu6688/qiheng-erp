package cn.erp.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import static cn.erp.workflow.WorkflowDocuments.*;

@Component
public class GenericWorkflowBusiness implements WorkflowBusiness {
    static final String TYPE = "wf_generic_request";
    private final JdbcTemplate jdbc;

    GenericWorkflowBusiness(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public String businessType() { return TYPE; }

    Map<String,Object> row(ActorScope actor,long id,boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM wf_generic_request WHERE tenant_id=? AND company_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),actor.tenantId(),actor.companyId(),id);
        if (rows.isEmpty()) throw missing();
        var row = rows.getFirst();
        OrgOwnership.require(actor,row);
        return row;
    }

    @Override public Map<String,Object> snapshot(ActorScope actor,long id) {
        var row = row(actor,id,false);
        var summary = new LinkedHashMap<String,Object>();
        summary.put("documentNo",row.get("request_no"));
        summary.put("title",row.get("title"));
        summary.put("createdBy",number(row.get("created_by")));
        summary.put("ownerDeptId",number(row.get("owner_department_id")));
        summary.put("modelId",number(row.get("model_id")));
        return summary;
    }

    @Override public void decide(ActorScope actor,long id,String outcome,String comment,String key) {
        if (!WorkflowContext.allows(actor,TYPE,id) || !Set.of("approved","rejected","cancelled","terminated").contains(outcome))
            throw new ApiException(403,"FORBIDDEN","通用申请须通过流程命令处理");
        row(actor,id,true);
        if (jdbc.update("UPDATE wf_generic_request SET status=? WHERE tenant_id=? AND company_id=? AND id=? AND status='pending'",
                outcome,actor.tenantId(),actor.companyId(),id) != 1)
            throw new ApiException(409,"STATE_CONFLICT","通用申请已结束");
    }

    public Map<String,Object> viewAttachments(CompanyAccess actor,long id) {
        var row = row(actor,id,false);
        if (number(row.get("created_by")) != actor.userId()) {
            long instance = number(row.get("wf_instance_id"));
            var tasks = jdbc.queryForList("""
                    SELECT 1 FROM wf_task_ext WHERE tenant_id=? AND company_id=? AND instance_id=? AND assignee_id=?
                      AND (status IN ('pending','waiting_sign','approved') OR status='rejected') LIMIT 1
                    """,actor.tenantId(),actor.companyId(),instance,actor.userId());
            if (tasks.isEmpty() || !actor.hasPermission(TYPE+":approve")) throw missing();
        }
        return Map.of("id",Long.toString(id),"status",row.get("status"));
    }

    public void modifyAttachments(CompanyAccess actor,long id) {
        var row = row(actor,id,false);
        if (!actor.hasPermission(TYPE+":create") || number(row.get("created_by")) != actor.userId()
                || !row.get("status").equals("pending")) throw missing();
    }
}
