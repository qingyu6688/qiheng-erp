package cn.erp.workflow;

import java.math.BigDecimal;
import java.util.*;

import cn.erp.shared.ApiException;

/** 用户仅提交受限节点树，表达式和执行器均由服务端生成。 */
public final class SimpleModel {
    private SimpleModel() {}

    public record Node(
            String id,
            String type,
            String name,
            Assignee assignee,
            String multi,
            Timeout timeout,
            List<Branch> branches,
            Node child,
            Boolean priceApproval,Boolean creditApproval,
            Node subprocess, Integer delaySeconds, String triggerEvent, Map<String,String> fieldAccess) {
        public Node(String id,String type,String name,Assignee assignee,String multi,Timeout timeout,List<Branch> branches,Node child,Boolean priceApproval) {this(id,type,name,assignee,multi,timeout,branches,child,priceApproval,null);}
        public Node(String id,String type,String name,Assignee assignee,String multi,Timeout timeout,List<Branch> branches,Node child,Boolean priceApproval,Boolean creditApproval) {
            this(id,type,name,assignee,multi,timeout,branches,child,priceApproval,creditApproval,null,null,null,null);
        }
    }

    public record Assignee(
            String strategy,
            List<Long> userIds,
            List<String> roleCodes,
            List<Long> postIds,
            Integer level,
            Boolean starterDepartment,
            String field,
            String emptyHandler) {}

    public record Timeout(Integer hours, String action, Integer repeat) {}

    public record Branch(String name, Condition condition, Boolean defaultBranch, Node child) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public Branch(
                @com.fasterxml.jackson.annotation.JsonProperty("name") String name,
                @com.fasterxml.jackson.annotation.JsonProperty("condition") Condition condition,
                @com.fasterxml.jackson.annotation.JsonProperty("default") Boolean defaultBranch,
                @com.fasterxml.jackson.annotation.JsonProperty("child") Node child) {
            this.name = name;
            this.condition = condition;
            this.defaultBranch = defaultBranch;
            this.child = child;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("default")
        public Boolean defaultBranch() {
            return defaultBranch;
        }
    }

    public record Condition(
            String field, String op, String value, List<Condition> all, List<Condition> any) {}

    public static final Set<String> TYPES =
            Set.of(
                    "sales_order",
                    "purchase_order",
                    "refund_request",
                    "document_reversal",
                    "stock_adjustment","credit_increase","wf_generic_request");
    static final Set<String> TRIGGERS = Set.of("checkpoint", "review_required");
    private static final Set<String> SELECTIVE = Set.of("condition_branch", "route_branch", "inclusive_branch");
    public static final Set<String> STRATEGIES =
            Set.of(
                    "users",
                    "role",
                    "dept_leader",
                    "multi_level_leader",
                    "post",
                    "starter_select",
                    "starter",
                    "field_user",
                    "starter_dept_members");
    public static final Map<String, String> FIELDS =
            Map.ofEntries(
                    Map.entry("totalAmount", "decimal"),
                    Map.entry("discountRate", "decimal"),
                    Map.entry("belowMinPrice", "boolean"),
                    Map.entry("overCreditLimit","boolean"),Map.entry("creditExposure","decimal"),Map.entry("creditLimit","decimal"),
                    Map.entry("ownerDeptId", "id"),
                    Map.entry("customerId", "id"),
                    Map.entry("supplierId", "id"),
                    Map.entry("requisitionApproved", "boolean"),
                    Map.entry("refundMethod", "string"),
                    Map.entry("sourceType", "string"),
                    Map.entry("crossPeriod", "boolean"),
                    Map.entry("adjustType", "string"),
                    Map.entry("salesUserId", "id"),
                    Map.entry("buyerUserId", "id"),
                    Map.entry("createdBy", "id"));

    public static Map<String, Node> validate(Node root, String businessType) {
        if (!TYPES.contains(businessType) || root == null || !"start".equals(root.type()))
            throw invalid("请选择支持的业务类型并保留发起人节点");
        var nodes = new LinkedHashMap<String, Node>();
        walk(root, nodes, 0);
        for (var node : nodes.values())
            if (Boolean.TRUE.equals(node.priceApproval())
                    && (!businessType.equals("sales_order") || !node.type().equals("approver")))
                throw invalid("人工价格审批只适用于销售订单的审批节点");
        for (var node : nodes.values()) {
            if (node.fieldAccess() != null && (!businessType.equals("wf_generic_request")
                    || !Set.of("approver", "handler").contains(node.type())
                    || node.fieldAccess().size() > 50
                    || node.fieldAccess().entrySet().stream().anyMatch(entry -> !WorkflowFormSchema.validKey(entry.getKey())
                            || !Set.of("read", "write", "hidden").contains(Objects.toString(entry.getValue(), "")))))
                throw invalid("字段读写权限仅适用于通用表单的审批或办理节点");
            if (node.assignee() != null
                    && node.assignee().strategy().equals("field_user")
                    && !fields(businessType).contains(node.assignee().field()))
                throw invalid("此类单据不提供所选人员字段");
            if (node.branches() != null)
                for (var branch : node.branches())
                    if (branch.condition() != null)
                        validateFields(branch.condition(), businessType);
        }
        if (minimumReviews(root) < 1) throw invalid("每条可达路径至少保留一个人工审批节点");
        for(var node:nodes.values()) if(Boolean.TRUE.equals(node.creditApproval())&&(!businessType.equals("sales_order")||!node.type().equals("approver")))throw invalid("人工信用审批仅适用于销售订单审批节点");
        return nodes;
    }

    private static void walk(Node node, Map<String, Node> nodes, int depth) {
        if (node == null) return;
        if (depth > 30
                || nodes.size() >= 100
                || node.id() == null
                || !node.id().matches("[A-Za-z][A-Za-z0-9_]{0,39}")
                || node.id().matches("(?:wf_|prepare_|join_|flow_|available_|resume_).*?")
                || nodes.putIfAbsent(node.id(), node) != null)
            throw invalid("节点编号重复、使用保留前缀或无效，最多100个节点、30层");
        safeLabel(node.name());
        if (!Set.of("start", "approver", "handler", "copy", "condition_branch", "parallel_branch",
                        "inclusive_branch", "route_branch", "subprocess", "delay", "trigger")
                        .contains(node.type())
                || depth > 0 && node.type().equals("start")) throw invalid("节点类型不支持或发起人位置无效");
        if (Set.of("approver", "handler", "copy").contains(node.type())) validateAssignee(node);
        else if (node.assignee() != null || node.timeout() != null || node.multi() != null)
            throw invalid("发起人和分支节点不能配置处理人或超时");
        if (node.type().equals("subprocess") != (node.subprocess() != null))
            throw invalid("子流程必须配置内部环节");
        if (node.type().equals("delay") != (node.delaySeconds() != null)
                || node.delaySeconds() != null && (node.delaySeconds() < 1 || node.delaySeconds() > 604800))
            throw invalid("延迟时间须为1至604800秒");
        if (node.type().equals("trigger") != (node.triggerEvent() != null)
                || node.triggerEvent() != null && !TRIGGERS.contains(node.triggerEvent()))
            throw invalid("请选择预定义的流程事件");
        if (node.type().equals("approver") || node.type().equals("handler")) {
            if (node.multi() != null && !Set.of("and", "or", "sequential").contains(node.multi()))
                throw invalid("请选择会签、或签或依次审批");
            if (node.assignee().strategy().equals("multi_level_leader")
                    && node.multi() != null
                    && !node.multi().equals("sequential"))
                throw invalid("连续多级上级固定采用依次审批，不能改为会签或或签");
            if (node.timeout() != null) {
                var timeout = node.timeout();
                if (timeout.hours() == null
                        || timeout.hours() < 1
                        || timeout.hours() > 720
                        || timeout.repeat() != null
                                && (timeout.repeat() < 1 || timeout.repeat() > 168)
                        || !Set.of("remind", "approve", "reject", "terminate")
                                .contains(Objects.toString(timeout.action(), "")))
                    throw invalid("超时须为1至720小时，重复提醒为1至168小时");
                if (node.type().equals("approver") && timeout.action().equals("approve"))
                    throw invalid("人工复核节点不能超时自动同意");
            }
        }
        boolean branch = node.type().endsWith("_branch");
        if (branch) {
            if (node.branches() == null
                    || node.branches().size() < 2
                    || node.branches().size() > 10) throw invalid("分支数须为2至10条");
            int defaults = 0;
            for (var item : node.branches()) {
                if (item == null
                        || item.name() == null
                        || item.name().isBlank()
                        || item.name().length() > 100) throw invalid("请填写分支名称");
                if (Boolean.TRUE.equals(item.defaultBranch())) defaults++;
                else if (SELECTIVE.contains(node.type()))
                    validateCondition(item.condition(), 0);
                walk(item.child(), nodes, depth + 1);
            }
            if (SELECTIVE.contains(node.type()) && defaults != 1)
                throw invalid("条件分支须有且仅有一条默认分支");
        } else if (node.branches() != null && !node.branches().isEmpty())
            throw invalid("普通节点不能携带分支");
        walk(node.subprocess(), nodes, depth + 1);
        walk(node.child(), nodes, depth + 1);
    }

    private static void validateAssignee(Node node) {
        var a = node.assignee();
        if (a == null || !STRATEGIES.contains(a.strategy())) throw invalid("请选择审批人或抄送人策略");
        if (a.level() != null && (a.level() < 1 || a.level() > 20)) throw invalid("上级层数须为1至20层");
        if (a.emptyHandler() != null
                && !Set.of("skip", "admin", "reject").contains(a.emptyHandler()))
            throw invalid("空审批人处理方式无效");
        if (node.type().equals("approver") && "skip".equals(a.emptyHandler()))
            throw invalid("人工审批节点找不到审批人时不能自动通过");
        if (a.strategy().equals("users") && (a.userIds() == null || a.userIds().isEmpty())
                || a.userIds() != null
                        && (a.userIds().size() > 100
                                || a.userIds().stream().anyMatch(id -> id == null || id <= 0)))
            throw invalid("指定用户须为1至100名有效用户");
        if (a.strategy().equals("role") && (a.roleCodes() == null || a.roleCodes().isEmpty())
                || a.roleCodes() != null
                        && (a.roleCodes().size() > 30
                                || a.roleCodes().stream()
                                        .anyMatch(
                                                code ->
                                                        code == null
                                                                || !code.matches(
                                                                        "[A-Za-z0-9_:-]{1,64}"))))
            throw invalid("请选择有效角色，最多30个");
        if (a.strategy().equals("post") && (a.postIds() == null || a.postIds().isEmpty())
                || a.postIds() != null
                        && (a.postIds().size() > 30
                                || a.postIds().stream().anyMatch(id -> id == null || id <= 0)))
            throw invalid("请选择有效岗位，最多30个");
        if (a.strategy().equals("field_user")
                && !Set.of("salesUserId", "buyerUserId", "createdBy")
                        .contains(Objects.toString(a.field(), "")))
            throw invalid("人员字段只能为业务员、采购员或创建人");
        if (a.strategy().equals("starter_dept_members") && !node.type().equals("copy"))
            throw invalid("部门全员策略仅用于抄送");
    }

    private static int minimumReviews(Node node) {
        if (node == null) return 0;
        int own = node.type().equals("approver") ? 1 : 0;
        if (SELECTIVE.contains(node.type()))
            own +=
                    node.branches().stream()
                            .mapToInt(b -> minimumReviews(b.child()))
                            .min()
                            .orElse(0);
        if (node.type().equals("parallel_branch"))
            own += node.branches().stream().mapToInt(b -> minimumReviews(b.child())).sum();
        return own + minimumReviews(node.subprocess()) + minimumReviews(node.child());
    }

    public static void validateCondition(Condition c, int depth) {
        if (c == null || depth > 5) throw invalid("请设置有效条件，最多6层");
        if (c.all() != null || c.any() != null) {
            var items = c.all() != null ? c.all() : c.any();
            if (c.field() != null
                    || c.all() != null && c.any() != null
                    || items.isEmpty()
                    || items.size() > 20) throw invalid("组合条件格式无效");
            items.forEach(item -> validateCondition(item, depth + 1));
            return;
        }
        if (!FIELDS.containsKey(Objects.toString(c.field(), ""))
                || !Set.of("==", "!=", ">", ">=", "<", "<=", "in")
                        .contains(Objects.toString(c.op(), ""))
                || c.value() == null
                || c.value().length() > 200) throw invalid("条件字段、运算或值无效");
        if (Set.of("decimal", "id").contains(FIELDS.get(c.field())))
            for (String value : c.value().split(",", -1)) {
                try {
                    if (new BigDecimal(value).precision() > 30) throw invalid("条件数字过长");
                } catch (NumberFormatException error) {
                    throw invalid("数字条件须填写有效数字");
                }
            }
        if (FIELDS.get(c.field()).equals("boolean") && !Set.of("true", "false").contains(c.value()))
            throw invalid("布尔条件只能填写true或false");
        if (Set.of("boolean", "string").contains(FIELDS.get(c.field()))
                && !Set.of("==", "!=", "in").contains(c.op())) throw invalid("文本和布尔条件仅支持等于、不等于或属于");
        if (!c.op().equals("in") && c.value().contains(",")) throw invalid("只有属于条件可以使用逗号分隔多个值");
    }

    public static Set<String> fields(String type) {
        if (type.equals("wf_generic_request")) return Set.of("ownerDeptId","createdBy");
        var result = new HashSet<>(Set.of("totalAmount", "ownerDeptId", "createdBy"));
        result.addAll(
                switch (type) {
                    case "sales_order" ->
                            Set.of("discountRate", "belowMinPrice", "customerId", "salesUserId");
                    case "purchase_order" ->
                            Set.of(
                                    "discountRate",
                                    "supplierId",
                                    "buyerUserId",
                                    "requisitionApproved");
                    case "refund_request" -> Set.of("customerId", "refundMethod");
                    case "document_reversal" -> Set.of("sourceType", "crossPeriod");
                    case "stock_adjustment" -> Set.of("adjustType");
                    default -> Set.of();
                });
        return result;
    }

    public static void validateFields(Condition condition, String type) {
        validateCondition(condition, 0);
        if (condition.all() != null) condition.all().forEach(c -> validateFields(c, type));
        else if (condition.any() != null) condition.any().forEach(c -> validateFields(c, type));
        else if (!fields(type).contains(condition.field()))
            throw invalid("此类单据不提供条件字段：" + condition.field());
    }

    public static List<Node> reachable(Node node, Map<String, Object> summary) {
        var result = new ArrayList<Node>();
        if (node == null) return result;
        result.add(node);
        if (SELECTIVE.contains(node.type())) {
            var selected = node.branches().stream().filter(branch -> !Boolean.TRUE.equals(branch.defaultBranch())
                    && matches(branch.condition(),summary));
            var matched = (node.type().equals("inclusive_branch") ? selected : selected.limit(1)).toList();
            if (matched.isEmpty()) matched = node.branches().stream().filter(branch -> Boolean.TRUE.equals(branch.defaultBranch())).toList();
            matched.forEach(branch -> result.addAll(reachable(branch.child(), summary)));
        } else if (node.type().equals("parallel_branch"))
            node.branches().forEach(b -> result.addAll(reachable(b.child(), summary)));
        result.addAll(reachable(node.subprocess(), summary));
        result.addAll(reachable(node.child(), summary));
        return result;
    }

    public static boolean matches(Condition c, Map<String, Object> values) {
        if (c == null) return true;
        if (c.all() != null) return c.all().stream().allMatch(item -> matches(item, values));
        if (c.any() != null) return c.any().stream().anyMatch(item -> matches(item, values));
        Object actual = values.get(c.field());
        if (actual == null) return false;
        String value = actual.toString();
        if (c.op().equals("in"))
            return Arrays.stream(c.value().split(",", -1))
                    .anyMatch(item -> compare(c.field(), value, item) == 0);
        int compared = compare(c.field(), value, c.value());
        return switch (c.op()) {
            case "==" -> compared == 0;
            case "!=" -> compared != 0;
            case ">" -> compared > 0;
            case ">=" -> compared >= 0;
            case "<" -> compared < 0;
            case "<=" -> compared <= 0;
            default -> false;
        };
    }

    private static int compare(String field, String a, String b) {
        return Set.of("decimal", "id").contains(FIELDS.get(field))
                ? new BigDecimal(a).compareTo(new BigDecimal(b))
                : a.compareTo(b);
    }

    static void safeLabel(String label) {
        if (label == null
                || label.isBlank()
                || label.length() > 100
                || label.contains("${")
                || label.contains("#{")
                || label.chars().anyMatch(Character::isISOControl)
                || label.codePoints().anyMatch(value -> value > 0xffff))
            throw invalid("名称须为1至100字，不能含控制字符、表达式或引擎不支持的扩展字符");
    }

    static ApiException invalid(String message) {
        return new ApiException(400, "WORKFLOW_MODEL_INVALID", message);
    }
}
