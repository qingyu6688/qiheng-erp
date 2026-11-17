package cn.erp.workflow;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;

/** 只生成本项目受支持的BPMN元素，不执行前端提交的XML或表达式。 */
final class SimpleCompiler {
    private final org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
    private FlowElementsContainer scope = process;
    private String rejection = "wf_rejected";
    private int sequence;

    static String compile(String key, String name, SimpleModel.Node root, String type) {
        SimpleModel.validate(root, type);
        var c = new SimpleCompiler();
        c.process.setId(key);
        c.process.setName(name);
        c.process.setExecutable(true);
        var start = new StartEvent();
        start.setId("wf_start");
        c.process.addFlowElement(start);
        var end = new EndEvent();
        end.setId("wf_end");
        c.process.addFlowElement(end);
        var finish = new ServiceTask();
        finish.setId("wf_finish");
        finish.setName("业务审批结果确认");
        finish.setImplementationType("delegateExpression");
        finish.setImplementation("${wfFinished}");
        c.process.addFlowElement(finish);
        c.link("wf_finish", "wf_end", null);
        c.rejection();
        String entry = c.node(root.child(), "wf_finish");
        c.link("wf_start", entry, null);
        var model = new BpmnModel();
        model.setTargetNamespace("erp:workflow");
        model.addProcess(c.process);
        return new String(new BpmnXMLConverter().convertToXML(model), StandardCharsets.UTF_8);
    }

    private String node(SimpleModel.Node node, String next) {
        if (node == null) return next;
        String after = node(node.child(), next);
        if (node.type().endsWith("_branch")) return branch(node, after);
        if (node.type().equals("subprocess")) return subprocess(node, after);
        if (node.type().equals("trigger")) {
            service(node.id(), node.name(), "${wfTrigger}");
            link(node.id(), after, null);
            return node.id();
        }
        if (node.type().equals("delay")) {
            var receive = new ReceiveTask();
            receive.setId(node.id());
            receive.setName(node.name());
            var listener = new FlowableListener();
            listener.setEvent("start");
            listener.setImplementationType("delegateExpression");
            listener.setImplementation("${wfDelay}");
            receive.setExecutionListeners(List.of(listener));
            scope.addFlowElement(receive);
            link(node.id(), after, null);
            return node.id();
        }
        var prepare = new ServiceTask();
        prepare.setId("prepare_" + node.id());
        prepare.setName(node.name());
        prepare.setImplementationType("delegateExpression");
        prepare.setImplementation("${wfPrepare}");
        scope.addFlowElement(prepare);
        if (node.type().equals("copy")) {
            link(prepare.getId(), after, null);
            return prepare.getId();
        }
        var task = new UserTask();
        task.setId(node.id());
        task.setName(node.name());
        task.setAssignee("${wfAssignee_" + node.id() + "}");
        var multi = new MultiInstanceLoopCharacteristics();
        multi.setInputDataItem("${wfUsers_" + node.id() + "}");
        multi.setElementVariable("wfAssignee_" + node.id());
        multi.setSequential(
                "sequential".equals(node.multi())
                        || node.assignee().strategy().equals("multi_level_leader"));
        if ("or".equals(node.multi()))
            multi.setCompletionCondition("${nrOfCompletedInstances > 0}");
        task.setLoopCharacteristics(multi);
        var listener = new FlowableListener();
        listener.setEvent("create");
        listener.setImplementationType("delegateExpression");
        listener.setImplementation("${wfTaskCreated}");
        task.setTaskListeners(List.of(listener));
        scope.addFlowElement(task);
        var available = new ExclusiveGateway();
        available.setId("available_" + node.id());
        scope.addFlowElement(available);
        link(prepare.getId(), available.getId(), null);
        link(available.getId(), rejection, "${wfReject_" + node.id() + "}");
        available.setDefaultFlow(link(available.getId(), task.getId(), null));
        link(task.getId(), after, null);
        return prepare.getId();
    }

    private String branch(SimpleModel.Node node, String next) {
        Gateway split = switch (node.type()) {
            case "parallel_branch" -> new ParallelGateway();
            case "inclusive_branch" -> new InclusiveGateway();
            default -> new ExclusiveGateway();
        };
        split.setId(node.id());
        split.setName(node.name());
        scope.addFlowElement(split);
        String join = next;
        if (split instanceof ParallelGateway || split instanceof InclusiveGateway) {
            Gateway gateway = split instanceof ParallelGateway ? new ParallelGateway() : new InclusiveGateway();
            gateway.setId("join_" + node.id());
            scope.addFlowElement(gateway);
            link(gateway.getId(), next, null);
            join = gateway.getId();
        }
        int index = 0;
        for (var branch : node.branches()) {
            String target = node(branch.child(), join);
            String expression =
                    !(split instanceof ParallelGateway)
                                    && !Boolean.TRUE.equals(branch.defaultBranch())
                            ? "${wfConditions.accept(execution, '"
                                    + node.id()
                                    + "', "
                                    + index
                                    + ")}"
                            : null;
            String id = link(split.getId(), target, expression);
            if (!(split instanceof ParallelGateway) && Boolean.TRUE.equals(branch.defaultBranch())) split.setDefaultFlow(id);
            index++;
        }
        return split.getId();
    }

    private String subprocess(SimpleModel.Node node, String next) {
        var child = new SubProcess();
        child.setId(node.id());
        child.setName(node.name());
        scope.addFlowElement(child);
        link(node.id(), next, null);
        var parent = scope;
        var parentRejection = rejection;
        scope = child;
        rejection = "wf_rejected_" + node.id();
        rejection();
        var start = new StartEvent();
        start.setId("wf_start_" + node.id());
        scope.addFlowElement(start);
        var end = new EndEvent();
        end.setId("wf_end_" + node.id());
        scope.addFlowElement(end);
        link(start.getId(), node(node.subprocess(), end.getId()), null);
        scope = parent;
        rejection = parentRejection;
        return node.id();
    }

    private void rejection() {
        service(rejection, "无可用处理人自动驳回", "${wfRejected}");
        var rejectedEnd = new EndEvent();
        rejectedEnd.setId(rejection + "_end");
        var terminate = new TerminateEventDefinition();
        terminate.setTerminateAll(true);
        rejectedEnd.setEventDefinitions(List.of(terminate));
        scope.addFlowElement(rejectedEnd);
        link(rejection, rejectedEnd.getId(), null);
    }

    private void service(String id, String name, String delegate) {
        var task = new ServiceTask();
        task.setId(id);
        task.setName(name);
        task.setImplementationType("delegateExpression");
        task.setImplementation(delegate);
        scope.addFlowElement(task);
    }

    private String link(String from, String to, String condition) {
        var flow = new SequenceFlow(from, to);
        flow.setId("flow_" + (++sequence));
        flow.setConditionExpression(condition);
        scope.addFlowElement(flow);
        return flow.getId();
    }
}
