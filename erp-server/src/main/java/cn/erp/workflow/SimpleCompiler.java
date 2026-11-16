package cn.erp.workflow;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.*;

/** 只生成本项目受支持的BPMN元素，不执行前端提交的XML或表达式。 */
final class SimpleCompiler {
    private final org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
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
        var rejected = new ServiceTask();
        rejected.setId("wf_rejected");
        rejected.setName("无可用处理人自动驳回");
        rejected.setImplementationType("delegateExpression");
        rejected.setImplementation("${wfRejected}");
        c.process.addFlowElement(rejected);
        var rejectedEnd = new EndEvent();
        rejectedEnd.setId("wf_rejected_end");
        rejectedEnd.setEventDefinitions(List.of(new TerminateEventDefinition()));
        c.process.addFlowElement(rejectedEnd);
        c.link("wf_rejected", rejectedEnd.getId(), null);
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
        var prepare = new ServiceTask();
        prepare.setId("prepare_" + node.id());
        prepare.setName(node.name());
        prepare.setImplementationType("delegateExpression");
        prepare.setImplementation("${wfPrepare}");
        process.addFlowElement(prepare);
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
        process.addFlowElement(task);
        var available = new ExclusiveGateway();
        available.setId("available_" + node.id());
        process.addFlowElement(available);
        link(prepare.getId(), available.getId(), null);
        link(available.getId(), "wf_rejected", "${wfReject_" + node.id() + "}");
        available.setDefaultFlow(link(available.getId(), task.getId(), null));
        link(task.getId(), after, null);
        return prepare.getId();
    }

    private String branch(SimpleModel.Node node, String next) {
        Gateway split =
                node.type().equals("parallel_branch")
                        ? new ParallelGateway()
                        : new ExclusiveGateway();
        split.setId(node.id());
        split.setName(node.name());
        process.addFlowElement(split);
        String join = next;
        if (split instanceof ParallelGateway) {
            var gateway = new ParallelGateway();
            gateway.setId("join_" + node.id());
            process.addFlowElement(gateway);
            link(gateway.getId(), next, null);
            join = gateway.getId();
        }
        int index = 0;
        for (var branch : node.branches()) {
            String target = node(branch.child(), join);
            String expression =
                    split instanceof ExclusiveGateway
                                    && !Boolean.TRUE.equals(branch.defaultBranch())
                            ? "${wfConditions.accept(execution, '"
                                    + node.id()
                                    + "', "
                                    + index
                                    + ")}"
                            : null;
            String id = link(split.getId(), target, expression);
            if (split instanceof ExclusiveGateway exclusive
                    && Boolean.TRUE.equals(branch.defaultBranch())) exclusive.setDefaultFlow(id);
            index++;
        }
        return split.getId();
    }

    private String link(String from, String to, String condition) {
        var flow = new SequenceFlow(from, to);
        flow.setId("flow_" + (++sequence));
        flow.setConditionExpression(condition);
        process.addFlowElement(flow);
        return flow.getId();
    }
}
