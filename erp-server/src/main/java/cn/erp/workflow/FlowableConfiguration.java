package cn.erp.workflow;

import java.util.*;
import javax.sql.DataSource;

import org.flowable.engine.*;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class FlowableConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    ProcessEngine workflowEngine(
            DataSource source,
            PlatformTransactionManager transactions,
            ObjectProvider<WorkflowRuntime> runtime) {
        var config = new SpringProcessEngineConfiguration();
        config.setDataSource(source);
        config.setTransactionManager(transactions);
        config.setDatabaseSchemaUpdate("false");
        config.setDisableIdmEngine(true);
        config.setDisableEventRegistry(true);
        config.setEnableSafeBpmnXml(true);
        config.setAsyncExecutorActivate(false);
        config.setHistoryLevel(org.flowable.common.engine.impl.history.HistoryLevel.AUDIT);
        // 一期不生成异步脚本或引擎定时器；超时由统一租约任务调用同事务命令。
        config.setBeans(
                new HashMap<>(
                        Map.of(
                                "wfPrepare",
                                (JavaDelegate) e -> runtime.getObject().prepare(e),
                                "wfTaskCreated",
                                (TaskListener) t -> runtime.getObject().created(t),
                                "wfFinished",
                                (JavaDelegate) e -> runtime.getObject().finished(e),
                                "wfRejected",
                                (JavaDelegate) e -> runtime.getObject().autoRejected(e),
                                "wfDelay",
                                (org.flowable.engine.delegate.ExecutionListener) e -> runtime.getObject().scheduleDelay(e),
                                "wfTrigger",
                                (JavaDelegate) e -> runtime.getObject().publishTrigger(e),
                                "wfConditions",
                                new Conditions(runtime))));
        return config.buildProcessEngine();
    }

    public static final class Conditions {
        private final ObjectProvider<WorkflowRuntime> runtime;

        Conditions(ObjectProvider<WorkflowRuntime> runtime) {
            this.runtime = runtime;
        }

        public boolean accept(
                org.flowable.engine.delegate.DelegateExecution execution, String node, int branch) {
            return runtime.getObject().condition(execution, node, branch);
        }
    }
}
