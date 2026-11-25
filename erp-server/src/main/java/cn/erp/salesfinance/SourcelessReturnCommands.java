package cn.erp.salesfinance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.WorkflowPort;
import static cn.erp.salesfinance.FinanceSupport.*;

/** 原单退货仍走原状态机；本类只处理无源模式的审批、冻结及入账。 */
@Component
class SourcelessReturnCommands {
    private final JdbcTemplate jdbc;
    private final SourcelessReturnDocuments documents;
    private final SourcelessReturnCostPlans plans;
    private final SourcelessReturnPosting posting;
    private final FinanceSupport support;
    private final LedgerService ledger;
    private final WorkflowPort workflow;

    SourcelessReturnCommands(JdbcTemplate jdbc,SourcelessReturnDocuments documents,SourcelessReturnCostPlans plans,
            SourcelessReturnPosting posting,FinanceSupport support,LedgerService ledger,WorkflowPort workflow) {
        this.jdbc=jdbc;this.documents=documents;this.plans=plans;this.posting=posting;this.support=support;this.ledger=ledger;this.workflow=workflow;
    }

    static void require(CompanyAccess actor,String action) {
        String permission="sales_return:"+(action.equals("reject")?"approve":action);
        if(!actor.hasPermission("sales_return:view") || !actor.hasPermission(permission)
                || Set.of("approve","reject").contains(action) && !actor.hasPermission("cost:view")
                || !Set.of("approve","reject").contains(action) && !actor.hasPermission("sales_return:sourceless"))
            throw new ApiException(403,"FORBIDDEN","没有当前无原单退货操作或成本复核权限");
    }

    List<String> actions(CompanyAccess actor,Map<String,Object> header) {
        var result=new ArrayList<String>();String status=header.get("status").toString();
        if(Set.of("draft","rejected").contains(status)) {allow(result,actor,"update");allow(result,actor,"submit");allow(result,actor,"void");}
        if(status.equals("approved")) {allow(result,actor,"post");allow(result,actor,"submit");allow(result,actor,"void");}
        if(status.equals("pending") && id(header,"created_by")!=actor.userId()) {allow(result,actor,"approve");allow(result,actor,"reject");}
        return result;
    }

    private void allow(List<String> result,CompanyAccess actor,String action) {
        String permission="sales_return:"+(action.equals("reject")?"approve":action);
        if(actor.hasPermission("sales_return:view") && actor.hasPermission(permission)
                && (Set.of("approve","reject").contains(action)?actor.hasPermission("cost:view"):actor.hasPermission("sales_return:sourceless"))) result.add(action);
    }

    void command(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines,String action,FinanceModels.Version input) {
        require(actor,action);long id=id(header,"id");
        LocalDate day=((java.sql.Date)header.get("business_date")).toLocalDate();
        if(input.businessDate()!=null && !input.businessDate().equals(day)) throw invalid("无原单退货须按已审核的业务日期处理；修改日期请作废后重建");
        long period=0;
        if(Set.of("submit","approve","post").contains(action)) period=support.period(actor,day);
        workflow.guard(actor,"sales_return",id,action);
        switch(action) {
            case "submit" -> {
                state(header,"draft","rejected","approved");documents.revalidate(actor,header,lines);plans.freeze(actor,header,lines);
                jdbc.update("UPDATE sales_return SET status='pending',submitted_by=?,submitted_at=UTC_TIMESTAMP(3),approved_by=NULL,approved_at=NULL,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                        actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),id);
                workflow.start(actor,"sales_return",id,input.workflowAssignees()==null?Map.of():input.workflowAssignees());
            }
            case "approve","reject" -> review(actor,header,lines,action,input.reason());
            case "post" -> {
                state(header,"approved");ledger.lockOpenPeriod(actor,period,day);documents.revalidate(actor,header,lines);
                plans.recheck(actor,header,lines);posting.post(actor,header,lines,period,day);
            }
            case "void" -> {
                state(header,"draft","rejected","approved");
                jdbc.update("UPDATE sales_return SET status='voided',voided_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",actor.userId(),actor.tenantId(),actor.companyId(),id);
            }
            default -> throw invalid("退货操作无效");
        }
    }

    private void review(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines,String action,String reason) {
        state(header,"pending");
        if(id(header,"created_by")==actor.userId()) throw new ApiException(422,"TWO_PERSON_REQUIRED","无原单退货审批人不能是制单人");
        if(action.equals("reject")) {
            if(reason==null || reason.isBlank() || reason.length()>200) throw invalid("请填写200字以内的驳回原因");
            finish(actor,header,"rejected");return;
        }
        documents.revalidate(actor,header,lines);plans.recheck(actor,header,lines);
        jdbc.update("UPDATE sales_return SET status='approved',approved_by=?,approved_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),header.get("id"));
    }

    void finish(CompanyAccess actor,Map<String,Object> header,String status) {
        state(header,"pending");
        jdbc.update("UPDATE sales_return SET status=?,voided_at=IF(?='voided',UTC_TIMESTAMP(3),voided_at),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                status,status,actor.userId(),actor.tenantId(),actor.companyId(),header.get("id"));
    }
}
