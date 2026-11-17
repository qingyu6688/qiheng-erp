package cn.erp.workflow;

import java.util.*;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import cn.erp.identity.*;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import static cn.erp.workflow.WorkflowDocuments.number;
import static cn.erp.workflow.WorkflowTestData.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowPhaseTwoIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowForms forms;
    @Autowired WorkflowTasks tasks;
    @Autowired WorkflowDelay delays;
    @Autowired ProcessEngine engine;
    WorkflowTestData data;

    @BeforeEach void setup() { data = new WorkflowTestData(jdbc,access,sales,models); }

    @AfterEach void cleanup() {
        for (String table : List.of("wf_delay","wf_generic_request","wf_form"))
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",data.tenant);
        data.cleanup(engine);
    }

    @Test void genericFormRunsSubprocessDelayAndInclusiveGateWithPerNodePermissions() {
        var permissionNode = new SimpleModel.Node("manager","approver","部门复核",
                users(data.manager),"and",null,null,null,false,false,null,null,null,
                Map.of("amount","write","description","read","restricted","hidden"));
        var subprocess = new SimpleModel.Node("inside","subprocess","子流程复核",null,null,null,null,null,
                false,false,permissionNode,null,null,null);
        var notification = new SimpleModel.Node("notify","trigger","审批交汇事件",null,null,null,null,null,
                false,false,null,null,"checkpoint",null);
        var rule = new SimpleModel.Condition("createdBy",">","0",null,null);
        var choices = List.of(new SimpleModel.Branch("部门复核",rule,false,subprocess),
                new SimpleModel.Branch("财务复核",rule,false,approve("finance",List.of(data.financeA),"and",null)),
                new SimpleModel.Branch("默认复核",null,true,approve("fallback",List.of(data.financeB),"and",null)));
        var branches = new SimpleModel.Node("choice","inclusive_branch","并行命中审批",null,null,null,choices,notification,false);
        var delay = new SimpleModel.Node("pause","delay","延迟后开始",null,null,null,null,branches,
                false,false,null,60,null,null);
        long model = publish(root(delay));
        long formId = createForm(model);
        var maker = data.actor(data.maker);
        var requestInput = new WorkflowForms.ApplicationInput(formId,0L,"费用申请",
                Map.of("amount","99.99","description","办公用品","restricted","受限资料"));
        String requestKey = key();
        var request = forms.create(maker,requestKey,requestInput);
        assertThat(forms.create(maker,requestKey,requestInput)).isEqualTo(request);
        long id = number(request.get("id")), instance = number(request.get("instanceId"));
        String execution = jdbc.queryForObject("SELECT execution_id FROM wf_delay WHERE tenant_id=? AND instance_id=?",
                String.class,data.tenant,instance);
        assertThat(delays.resume(data.tenant,instance,execution)).isFalse();
        jdbc.update("UPDATE wf_delay SET due_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE tenant_id=? AND instance_id=?",data.tenant,instance);
        assertThat(delays.resume(data.tenant,instance,execution)).isTrue();
        assertThat(delays.resume(data.tenant,instance,execution)).isFalse();
        assertThat(pendingUsers(instance)).containsExactlyInAnyOrder(data.manager,data.financeA);
        long managerTask = task(instance,data.manager);
        var manager = data.actor(data.manager);
        var detail = forms.detail(manager,id,managerTask);
        assertThat(detail.toString()).contains("办公用品", "99.99", "write").doesNotContain("受限资料","restricted");
        assertThatThrownBy(() -> forms.detail(data.actor(data.financeB),id,null)).isInstanceOf(ApiException.class);
        var wrongTenant = new CompanyAccess(Ids.next(),manager.userId(),data.company,true,Set.of(),manager.permissions());
        var wrongCompany = new CompanyAccess(data.tenant,manager.userId(),Ids.next(),true,Set.of(),manager.permissions());
        for (var actor : List.of(wrongTenant,wrongCompany))
            assertThatThrownBy(() -> forms.detail(actor,id,managerTask)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tasks.decide(manager,managerTask,"approve",key(),decision(Map.of("restricted","篡改资料"))))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(403));
        assertThatThrownBy(() -> tasks.decide(manager,managerTask,"approve",key(),decision(Map.of("description","篡改只读"))))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(403));
        tasks.decide(manager,managerTask,"approve",key(),decision(Map.of("amount","100.00")));
        assertThat(forms.detail(maker,id,null).get("status")).isEqualTo("pending");
        tasks.decide(data.actor(data.financeA),task(instance,data.financeA),"approve",key(),decision(Map.of()));
        assertThat(forms.detail(maker,id,null).toString()).contains("approved","100.00","受限资料");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND event_type='workflow.trigger'",
                Long.class,data.tenant)).isEqualTo(1);
    }

    @Test void orderedRouteRunsOnlyFirstMatchingBranch() {
        var condition = new SimpleModel.Condition("createdBy",">","0",null,null);
        var route = new SimpleModel.Node("route","route_branch","优先路由",null,null,null,List.of(
                new SimpleModel.Branch("第一",condition,false,approve("first",List.of(data.manager),"and",null)),
                new SimpleModel.Branch("第二",condition,false,approve("second",List.of(data.financeA),"and",null)),
                new SimpleModel.Branch("后备",null,true,approve("fallback",List.of(data.financeB),"and",null))),null,false);
        var created = forms.create(data.actor(data.maker),key(),new WorkflowForms.ApplicationInput(createForm(publish(root(route))),0L,
                "流程分支验证",Map.of("amount","1","description","说明","restricted","内部字段")));
        assertThat(pendingUsers(number(created.get("instanceId")))).containsExactly(data.manager);
    }

    private long publish(SimpleModel.Node tree) {
        var maker = data.actor(data.maker);
        long model = number(models.save(maker,null,key(),new WorkflowModels.Input(data.company,"P"+Ids.next(),"通用流程",null,
                GenericWorkflowBusiness.TYPE,tree,0L)).get("id"));
        models.change(maker,model,"publish",0,key());
        return model;
    }

    private long createForm(long model) {
        return number(forms.save(data.actor(data.maker),null,key(),new WorkflowForms.FormInput("expense","费用申请",model,
                List.of(new WorkflowFormSchema.Field("amount","金额","number",true,null),
                        new WorkflowFormSchema.Field("description","说明","text",true,null),
                        new WorkflowFormSchema.Field("restricted","受限字段","text",false,null)),true,0L)).get("id"));
    }

    private List<Long> pendingUsers(long instance) {
        return jdbc.queryForList("SELECT assignee_id FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND status='pending'",
                Long.class,data.tenant,instance);
    }

    private long task(long instance,long user) {
        return jdbc.queryForObject("SELECT id FROM wf_task_ext WHERE tenant_id=? AND instance_id=? AND assignee_id=? AND status='pending'",
                Long.class,data.tenant,instance,user);
    }

    private SimpleModel.Assignee users(long user) { return new SimpleModel.Assignee("users",List.of(user),null,null,null,null,null,"reject"); }

    private WorkflowTasks.Decision decision(Map<String,Object> fields) {
        return new WorkflowTasks.Decision(0L,"同意",null,null,null,null,null,fields);
    }
}
