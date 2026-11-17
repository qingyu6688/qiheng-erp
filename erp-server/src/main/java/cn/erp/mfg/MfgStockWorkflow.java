package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.WorkflowBusiness;
import cn.erp.shared.WorkflowContext;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.mfg.MfgStockModels.Type.ISSUE;
import static cn.erp.mfg.MfgStockModels.Type.RECEIPT;
import static cn.erp.salesfinance.FinanceSupport.id;

class MfgStockWorkflow implements WorkflowBusiness {
    private final MfgStockQueries documents;
    private final MfgStockDocuments commands;
    private final MfgStockLines lines;
    private final MfgStockModels.Type type;

    MfgStockWorkflow(MfgStockQueries documents, MfgStockDocuments commands, MfgStockLines lines, MfgStockModels.Type type) {
        this.documents = documents;
        this.commands = commands;
        this.lines = lines;
        this.type = type;
    }

    @Override public String businessType() { return type.head; }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot(ActorScope actor, long document) {
        var scope = company(actor);
        var row = documents.header(scope, type, document, false);
        boolean overIssue = type == ISSUE && ("draft".equals(row.get("status")) && "issue".equals(row.get("direction"))
                ? lines.overIssue(scope, id(row, "work_order_id"), documents.lines(scope, ISSUE, document, false), false)
                : MfgStockPosting.flag(row.get("over_issue")));
        String label = type == ISSUE ? "生产领退料 " : "生产完工入库 ";
        return Map.of("documentNo", row.get("document_no"), "title", label + row.get("document_no"),
                "createdBy", row.get("created_by"),
                "submittedBy", row.get("submitted_by") == null ? row.get("created_by") : row.get("submitted_by"),
                "ownerDeptId", row.get("owner_department_id"), "totalAmount", "0",
                "overIssue", overIssue);
    }

    @Override
    @Transactional
    public void decide(ActorScope actor, long id, String outcome, String comment, String key) {
        if (!WorkflowContext.allows(actor, businessType(), id)) throw forbidden();
        commands.workflowDecide(company(actor), type, id, outcome, comment);
    }

    private CompanyAccess company(ActorScope actor) {
        if (actor instanceof CompanyAccess company && company.hasPermission(type.permission("view"))) return company;
        throw forbidden();
    }

    private ApiException forbidden() { return new ApiException(403, "FORBIDDEN", "没有处理生产单据审批的权限"); }
}

@Configuration(proxyBeanMethods = false)
class MfgStockWorkflowConfiguration {
    @Bean
    WorkflowBusiness mfgIssueWorkflow(MfgStockQueries queries, MfgStockDocuments documents, MfgStockLines lines) {
        return new MfgStockWorkflow(queries, documents, lines, ISSUE);
    }

    @Bean
    WorkflowBusiness mfgReceiptWorkflow(MfgStockQueries queries, MfgStockDocuments documents, MfgStockLines lines) {
        return new MfgStockWorkflow(queries, documents, lines, RECEIPT);
    }
}
