package cn.erp.workflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 默认流程动态授予待办，授权策略继续使用原审批权限和原单据范围。 */
public final class SystemWorkflowPlan {
    static final String CODE_PREFIX = "system20_";
    static final int PRIORITY = -1001;
    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("sales_order", "销售订单"), Map.entry("purchase_order", "采购订单"),
            Map.entry("refund_request", "退款申请"), Map.entry("sales_return", "无原单销售退货"), Map.entry("document_reversal", "冲销申请"),
            Map.entry("stock_adjustment", "库存调整"), Map.entry("credit_increase", "信用提额"),
            Map.entry("crm_contract", "客户合同"), Map.entry("mfg_issue_doc", "生产领退料"),
            Map.entry("purchase_requisition", "请购单"), Map.entry("stock_count", "库存盘点"),
            Map.entry("stock_misc_doc", "其他出入库"), Map.entry("gl_voucher", "总账凭证"),
            Map.entry("mfg_bom", "物料清单"), Map.entry("mfg_receipt_doc", "生产完工入库"),
            Map.entry("qc_inspection", "来料检验"), Map.entry("ar_bad_debt", "坏账处理"), Map.entry("price_adjustment", "价格调整"),Map.entry("purchase_fee_doc", "采购费用单"),Map.entry("sales_price_adjustment", "销售调价"));

    private SystemWorkflowPlan() {}

    private static final Set<String> REVERSAL_SOURCES = Set.of(
            "shipment", "sales_return", "goods_receipt", "purchase_return",
            "receipt", "payment", "account_transfer");

    /** 工作流保存业务类型，共享实体通过显式映射取得表名。 */
    public static String sourceTable(String type) {
        if(type==null || !LABELS.containsKey(type) && !REVERSAL_SOURCES.contains(type)
                && !type.equals("wf_generic_request"))throw SimpleModel.invalid("工作流业务类型无效");
        return type.equals("sales_price_adjustment")?"price_adjustment":type;
    }

    public static String sourceKind(String type,String alias) {
        if(!alias.matches("[a-z][a-z0-9_]*"))throw SimpleModel.invalid("工作流来源别名无效");
        return type.equals("sales_price_adjustment")?alias+".kind='sales'":type.equals("price_adjustment")?alias+".kind='purchase'":"1=1";
    }

    public static String pendingStatus(String type) {
        return switch (type) {
            case "refund_request" -> "pending_review";
            case "stock_count", "qc_inspection" -> "submitted";
            default -> "pending";
        };
    }

    static SimpleModel.Node tree(String type) {
        SimpleModel.Node next = review("review", "业务审批", false, false);
        if (type.equals("sales_order")) {
            var price = new SimpleModel.Condition("priceApprovalRequired", "==", "true", null, null);
            var credit = new SimpleModel.Condition("creditApprovalRequired", "==", "true", null, null);
            var both = new SimpleModel.Condition(null, null, null, List.of(price, credit), null);
            next = new SimpleModel.Node("route", "condition_branch", "审批条件", null, null, null,
                    List.of(new SimpleModel.Branch("价格与信用", both, false, review("both", "价格与信用审批", true, true)),
                            new SimpleModel.Branch("价格", price, false, review("price", "价格审批", true, false)),
                            new SimpleModel.Branch("信用", credit, false, review("credit", "信用审批", false, true)),
                            new SimpleModel.Branch("普通", null, true, next)), null, false);
        }
        return new SimpleModel.Node("start", "start", "提交申请", null, null, null, null, next, false);
    }

    private static SimpleModel.Node review(String id, String label, boolean price, boolean credit) {
        var assignment = new SimpleModel.Assignee("approval_permission", null, null, null, null, null, null, null);
        return new SimpleModel.Node(id, "approver", label, assignment, "or", null, null, null, price, credit);
    }
}
