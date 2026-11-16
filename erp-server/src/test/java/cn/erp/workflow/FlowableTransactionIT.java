package cn.erp.workflow;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;

import org.flowable.engine.*;
import org.flowable.engine.delegate.JavaDelegate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesorder.*;
import cn.erp.shared.Ids;

import static org.assertj.core.api.Assertions.*;

/** 真实MySQL、真实业务命令和真实Flowable，不以模拟事务证明原子性。 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FlowableTransactionIT.ProbeConfiguration.class)
class FlowableTransactionIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired SalesOrderService sales;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProcessEngine engine;
    @Autowired ProbeCallback callback;
    TransactionTemplate tx;
    long tenant, company, warehouse, customer, unit, sku;
    CompanyAccess maker, reviewer;
    String definition;
    static final LocalDate DAY = LocalDate.of(2026, 11, 15);
    static final Set<String> PERMISSIONS =
            Set.of(
                    "sales_order:create",
                    "sales_order:view",
                    "sales_order:submit",
                    "sales_order:approve",
                    "inventory:view");

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        ProbeCallback probeCallback(SalesOrderService sales) {
            return new ProbeCallback(sales);
        }

        @Bean
        org.springframework.beans.factory.SmartInitializingSingleton registerProbe(
                ProcessEngine engine, ProbeCallback callback) {
            return () ->
                    ((org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl)
                                    engine.getProcessEngineConfiguration())
                            .getBeans()
                            .put("probeCallback", callback);
        }
    }

    static class ProbeCallback implements JavaDelegate {
        final SalesOrderService sales;
        final AtomicBoolean fail = new AtomicBoolean();

        ProbeCallback(SalesOrderService sales) {
            this.sales = sales;
        }

        @Override
        public void execute(org.flowable.engine.delegate.DelegateExecution execution) {
            var actor =
                    new CompanyAccess(
                            ((Number) execution.getVariable("tenant")).longValue(),
                            ((Number) execution.getVariable("reviewer")).longValue(),
                            ((Number) execution.getVariable("company")).longValue(),
                            true,
                            Set.of(),
                            PERMISSIONS);
            sales.command(
                    actor,
                    ((Number) execution.getVariable("order")).longValue(),
                    "approve",
                    "probe-" + execution.getProcessInstanceId(),
                    new SalesOrderModels.Command(1L, "流程事务验证", null));
            if (fail.get()) throw new IllegalStateException("回调后故障注入");
        }
    }

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactions);
        tenant = Ids.next();
        company = Ids.next();
        warehouse = Ids.next();
        customer = Ids.next();
        unit = Ids.next();
        sku = Ids.next();
        long category = Ids.next(), product = Ids.next();
        maker = new CompanyAccess(tenant, Ids.next(), company, true, Set.of(), PERMISSIONS);
        reviewer = new CompanyAccess(tenant, Ids.next(), company, true, Set.of(), PERMISSIONS);
        callback.fail.set(false);
        jdbc.update(
                "INSERT INTO tenant(id,code,name) VALUES(?,?,'工作流事务验证')",
                tenant,
                "WFPROBE" + tenant);
        jdbc.update(
                "INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','验证公司')",
                company,
                tenant);
        jdbc.update(
                "INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",
                Ids.next(),
                tenant,
                company);
        jdbc.update(
                "INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','验证仓库')",
                warehouse,
                tenant,
                company);
        jdbc.update(
                "INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','验证客户')",
                customer,
                tenant);
        jdbc.update(
                "INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive)"
                    + " VALUES(?,?,?,'customer',1)",
                Ids.next(),
                tenant,
                customer);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')", unit, tenant);
        jdbc.update(
                "INSERT INTO product_category(id,tenant_id,code,name,path)"
                    + " VALUES(?,?,'C','验证分类','/')",
                category,
                tenant);
        jdbc.update(
                "INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id)"
                    + " VALUES(?,?,?,'P','验证商品',?)",
                product,
                tenant,
                category,
                unit);
        jdbc.update(
                "INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price)"
                    + " VALUES(?,?,?,'S','验证规格',100)",
                sku,
                tenant,
                product);
        jdbc.update(
                "INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base)"
                    + " VALUES(?,?,?,?,1,1)",
                Ids.next(),
                tenant,
                sku,
                unit);
        jdbc.update(
                "INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date)"
                    + " VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",
                Ids.next(),
                tenant,
                company);
        jdbc.update(
                "INSERT INTO"
                    + " inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,on_hand_qty)"
                    + " VALUES(?,?,?,?,?,10)",
                Ids.next(),
                tenant,
                company,
                warehouse,
                sku);
        jdbc.update(
                "INSERT INTO"
                    + " inventory_cost(id,tenant_id,company_id,warehouse_id,sku_id,qty,total_cost)"
                    + " VALUES(?,?,?,?,?,10,1000)",
                Ids.next(),
                tenant,
                company,
                warehouse,
                sku);
        String xml =
                """
<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="erp:probe">
<process id="transactionProbe" name="真实业务事务验证" isExecutable="true"><startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="review"/>
<userTask id="review" name="人工审批"/><sequenceFlow id="b" sourceRef="review" targetRef="business"/>
<serviceTask id="business" name="原业务命令" flowable:delegateExpression="${probeCallback}"/><sequenceFlow id="c" sourceRef="business" targetRef="end"/><endEvent id="end"/></process></definitions>
""";
        var deployment =
                engine.getRepositoryService()
                        .createDeployment()
                        .tenantId(Long.toString(tenant))
                        .addString("transaction-probe.bpmn20.xml", xml)
                        .deploy();
        definition =
                engine.getRepositoryService()
                        .createProcessDefinitionQuery()
                        .deploymentId(deployment.getId())
                        .singleResult()
                        .getId();
    }

    long create() {
        var line =
                new SalesOrderModels.Line(
                        null,
                        Long.toString(sku),
                        Long.toString(warehouse),
                        Long.toString(unit),
                        "1",
                        "100",
                        null,
                        "0",
                        null,
                        null);
        return Long.parseLong(
                sales.create(
                                maker,
                                key(),
                                new SalesOrderModels.Input(
                                        Long.toString(customer),
                                        DAY,
                                        DAY.plusDays(2),
                                        true,
                                        "0",
                                        List.of(line),
                                        List.of(),
                                        null,
                                        null,
                                        null))
                        .get("id")
                        .toString());
    }

    String start(long id) {
        sales.command(maker, id, "submit", key(), new SalesOrderModels.Command(0L, "提交验证", null));
        return engine.getRuntimeService()
                .startProcessInstanceById(
                        definition,
                        "probe:" + id,
                        Map.of(
                                "order",
                                id,
                                "tenant",
                                tenant,
                                "company",
                                company,
                                "reviewer",
                                reviewer.userId()))
                .getId();
    }

    @Test
    void businessSubmitAndEngineStartRollbackTogether() {
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            start(create());
                                            throw new IllegalStateException("启动后故障注入");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("sales_order")).isZero();
        assertThat(count("idempotency_record")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceTenantId(Long.toString(tenant))
                                .count())
                .isZero();
    }

    @Test
    void originalApprovalFailureRollsBackEngineCompletionStockAndIdempotencyThenCanRetry() {
        long id = create();
        String process = tx.execute(status -> start(id));
        var task =
                engine.getTaskService().createTaskQuery().processInstanceId(process).singleResult();
        callback.fail.set(true);
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> engine.getTaskService().complete(task.getId())))
                .isInstanceOf(RuntimeException.class);
        assertThat(state(id)).isEqualTo("pending");
        assertThat(count("inventory_reservation")).isZero();
        assertThat(engine.getTaskService().createTaskQuery().taskId(task.getId()).count())
                .isEqualTo(1);
        callback.fail.set(false);
        tx.executeWithoutResult(status -> engine.getTaskService().complete(task.getId()));
        assertThat(state(id)).isEqualTo("approved");
        assertThat(count("inventory_reservation")).isEqualTo(1);
        assertThat(
                        engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceId(process)
                                .count())
                .isZero();
    }

    @Test
    void independentConnectionCannotSeeUncommittedBusinessOrEngineRows() {
        var saved = new AtomicLong();
        tx.executeWithoutResult(
                status -> {
                    long id = create();
                    saved.set(id);
                    String process = start(id);
                    try (var connection = dataSource.getConnection();
                            var statement =
                                    connection.prepareStatement(
                                            "SELECT (SELECT COUNT(*) FROM sales_order WHERE"
                                                + " id=?)+(SELECT COUNT(*) FROM ACT_RU_EXECUTION"
                                                + " WHERE PROC_INST_ID_=?)")) {
                        statement.setLong(1, id);
                        statement.setString(2, process);
                        try (var rows = statement.executeQuery()) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getInt(1)).isZero();
                        }
                    } catch (java.sql.SQLException error) {
                        throw new IllegalStateException("独立连接验证失败", error);
                    }
                });
        assertThat(state(saved.get())).isEqualTo("pending");
        assertThat(
                        engine.getTaskService()
                                .createTaskQuery()
                                .taskTenantId(Long.toString(tenant))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void engineStartFailureRollsBackOriginalSubmitAndCommandKeyCanBeRetried() {
        long id = create();
        String commandKey = key();
        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            sales.command(
                                                    maker,
                                                    id,
                                                    "submit",
                                                    commandKey,
                                                    new SalesOrderModels.Command(0L, "提交验证", null));
                                            engine.getRuntimeService()
                                                    .startProcessInstanceById("不存在的定义");
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(state(id)).isEqualTo("draft");
        sales.command(
                maker, id, "submit", commandKey, new SalesOrderModels.Command(0L, "提交验证", null));
        assertThat(state(id)).isEqualTo("pending");
    }

    long count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Long.class, tenant);
    }

    String state(long id) {
        return jdbc.queryForObject(
                "SELECT status FROM sales_order WHERE tenant_id=? AND id=?",
                String.class,
                tenant,
                id);
    }

    String key() {
        return UUID.randomUUID().toString();
    }
}
