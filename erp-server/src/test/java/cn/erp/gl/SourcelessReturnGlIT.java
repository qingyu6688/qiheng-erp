package cn.erp.gl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import cn.erp.identity.CompanyAccess;
import cn.erp.jobs.OutboxDelivery;
import cn.erp.jobs.OutboxService;
import cn.erp.opening.OpeningService;
import cn.erp.period.PeriodService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.RefundApplicationService;
import cn.erp.salesfinance.SalesReturnService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import cn.erp.workflow.WorkflowModels;
import cn.erp.workflow.WorkflowTasks;
import static cn.erp.gl.GlTestData.key;
import static cn.erp.gl.GlSupport.number;
import static org.assertj.core.api.Assertions.*;

/** 真实退货、另一人工作流、资金登记及事件投递共同生成业务账和总账。 */
@SpringBootTest
@ActiveProfiles("test")
class SourcelessReturnGlIT {
    private static final LocalDate DAY=LocalDate.of(2026,11,15);
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlQueryService financial;
    @Autowired GlMappingResolver mappings;
    @Autowired SalesReturnService returns;
    @Autowired RefundApplicationService refunds;
    @Autowired OpeningService opening;
    @Autowired PeriodService periods;
    @Autowired ReversalService reversals;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks tasks;
    @Autowired ProcessEngine engine;
    @Autowired OutboxService outbox;
    @Autowired OutboxDelivery delivery;
    @Autowired ReconciliationChecks checks;
    private GlTestData d;
    private long warehouse,unit,sku,department;

    @BeforeEach void prepare() {
        d=new GlTestData(jdbc);warehouse=Ids.next();unit=Ids.next();sku=Ids.next();department=Ids.next();
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),d.tenant,d.company);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','无原单退货总账仓')",warehouse,d.tenant,d.company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,d.tenant);
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','退货分类','/')",category,d.tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','退回商品',?)",product,d.tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'S','退回商品',0.13)",sku,d.tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),d.tenant,sku,unit);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),d.tenant,d.company,d.bank);
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth) VALUES(?,?,?,'D','历史销售复核部门',?,1)",department,d.tenant,d.company,"/"+department+"/");
        actualPermissions();
        priorCash();activateBook();
    }

    private void priorCash() {
        long september=Ids.next();
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202609','2026-09-01','2026-09-30')",september,d.tenant,d.company);
        long cash=id(opening.createEntry(actor(d.maker),key(),new OpeningService.Entry("cash",null,d.bank,"D","1000",LocalDate.of(2026,9,30),"启用前真实资金期初",null)));
        opening.command(actor(d.reviewer),false,cash,"approve",key(),new OpeningService.Version(0L,null));
        opening.command(actor(d.maker),false,cash,"post",key(),new OpeningService.Version(1L,null));
        dispatch();
        periods.close(actor(d.reviewer),september,key(),new PeriodService.Version(0L,"结清账套启用前期间"));
    }

    @SuppressWarnings("unchecked") private void activateBook() {
        books.create(actor(d.maker),key(),new GlModels.Book("enterprise",d.october,null,0L));
        var suggested=(List<Map<String,Object>>)books.suggest(actor(d.maker)).get("rows");
        assertThat(suggested).hasSize(2);
        var lines=suggested.stream().map(row->{
            String debit=row.get("openingDebit").toString(),credit=row.get("openingCredit").toString();
            return new GlModels.OpeningLine(number(row.get("subjectId")),GlModels.Aux.empty(),debit,credit,"0","0",debit,credit,"0");
        }).toList();
        books.saveOpenings(actor(d.maker),key(),new GlModels.Openings(0L,lines));
        var confirmed=books.confirm(actor(d.reviewer),key(),new GlModels.Confirmation(number(books.get(actor(d.maker)).get("version")),"核对启用前资金承接与默认退货、退款映射"));
        books.activate(actor(d.maker),"activate",key(),new GlModels.Version(number(confirmed.get("version")),null));
    }

    private void actualPermissions() {
        long role=Ids.next();
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,'总账业务验收角色')",role,d.tenant,"GL"+role);
        for(long permission:jdbc.queryForList("SELECT id FROM sys_permission",Long.class))
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),d.tenant,role,permission);
        for(long user:List.of(d.maker,d.reviewer)) {
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),d.tenant,user,role);
            jdbc.update("INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(?,?,?)",d.tenant,d.company,user);
            jdbc.update("INSERT INTO org_user_department(tenant_id,company_id,user_id,department_id,is_primary) VALUES(?,?,?,?,1)",d.tenant,d.company,user,department);
        }
    }

    @AfterEach void cleanup() {
        if(d==null)return;
        for(var deployment:engine.getRepositoryService().createDeploymentQuery().deploymentTenantId(Long.toString(d.tenant)).list())
            engine.getRepositoryService().deleteDeployment(deployment.getId(),true);
        for(String table:List.of("notify_send_log","notify_message","wf_copy","wf_task_action","wf_task_ext","wf_instance","wf_business_binding","wf_model_version","wf_model","wf_category","approval_record"))
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        jdbc.update("DELETE FROM receivable WHERE tenant_id=? AND posting_action='reverse'",d.tenant);
        jdbc.update("DELETE FROM inventory_movement WHERE tenant_id=? AND posting_action='reverse'",d.tenant);
        for(String table:List.of("sales_return_line","sales_return","refund_request","document_reversal","org_user_department","org_user_company"))
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        d.cleanup();
    }

    @ParameterizedTest @CsvSource({"manual,5,0","average,7,70"})
    void returnAndReversalReachTheRealGeneralLedgerAndAllThreeStatements(String source,String unitCost,String openingCost) {
        if(source.equals("average"))stockOpening();
        long id=postedReturn(source.equals("manual")?unitCost:null);
        var journal=jdbc.queryForMap("SELECT * FROM journal WHERE tenant_id=? AND source_type='sales_return' AND source_id=?",d.tenant,id);
        var ar=jdbc.queryForMap("SELECT * FROM journal_line WHERE journal_id=? AND account_code='AR'",journal.get("id"));
        assertThat(mappings.context(actor(d.maker),journal,ar)).containsEntry("sourceType","sales_return").containsEntry("deptId",Long.toString(department));
        dispatch();
        var cost=new BigDecimal(unitCost).multiply(new BigDecimal("4"));
        assertBalances(new BigDecimal(openingCost).add(cost),cost.negate(),new BigDecimal("-45.20"));
        assertThat(balance("SALES_REVENUE")).isEqualByComparingTo("40");
        assertThat(balance("OUTPUT_VAT")).isEqualByComparingTo("5.20");
        assertThat(income("net_profit")).isEqualByComparingTo(cost.subtract(new BigDecimal("40")));
        consistent();
        models.installDefault(d.tenant,d.company,"document_reversal");
        long reversal=id(reversals.create(actor(d.maker),key(),new ReversalModels.Input("sales_return",id,DAY,"核对后冲回无原单退货")));
        review("document_reversal",reversal);
        reversals.command(actor(d.maker),reversal,"post",key(),new ReversalModels.Version(version("document_reversal",reversal),null));
        dispatch();
        assertBalances(new BigDecimal(openingCost),BigDecimal.ZERO,BigDecimal.ZERO);
        assertThat(balance("SALES_REVENUE")).isZero();assertThat(balance("OUTPUT_VAT")).isZero();
        assertThat(income("net_profit")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment WHERE tenant_id=?",Integer.class,d.tenant)).isZero();
        consistent();
    }

    @Test void explicitCreditRefundReachesBankAndGeneralLedgerWithoutAnOriginalReceipt() {
        long source=postedReturn("5");
        long credit=jdbc.queryForObject("SELECT i.id FROM partner_open_item i JOIN receivable r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id AND r.id=i.source_id AND i.item_type='receivable' WHERE i.tenant_id=? AND r.source_type='sales_return_line' AND r.source_id=?",Long.class,d.tenant,source);
        models.installDefault(d.tenant,d.company,"refund_request");
        long refund=id(refunds.create(actor(d.maker),key(),new FinanceModels.Refund(d.customer,null,d.bank,"20",DAY,"历史退货实际退款",List.of(credit),null,"sourceless_credit")));
        refunds.command(actor(d.maker),refund,"submit",key(),new FinanceModels.Version(version("refund_request",refund),null,null));
        review("refund_request",refund);
        String paidKey=key();var paid=new FinanceModels.Paid(version("refund_request",refund),DAY,"真实银行回单-GL01");
        refunds.paid(actor(d.maker),refund,paidKey,paid);refunds.paid(actor(d.maker),refund,paidKey,paid);
        dispatch();
        assertBalances(new BigDecimal("20"),new BigDecimal("-20"),new BigDecimal("-25.20"));
        assertThat(balance("FUNDS")).isEqualByComparingTo("980");
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE tenant_id=? AND cash_account_id=?",BigDecimal.class,d.tenant,d.bank)).isEqualByComparingTo("980");
        assertThat(jdbc.queryForObject("SELECT open_amount FROM partner_open_item WHERE id=?",BigDecimal.class,credit)).isEqualByComparingTo("25.20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE tenant_id=?",Integer.class,d.tenant)).isZero();
        assertThat(statementAmount("cash_flow","opening_cash")).isEqualByComparingTo("1000");
        assertThat(statementAmount("cash_flow","net_change")).isEqualByComparingTo("-20");
        assertThat(statementAmount("cash_flow","closing_cash")).isEqualByComparingTo("980");
        assertThatThrownBy(()->reversals.create(actor(d.maker),key(),new ReversalModels.Input("sales_return",source,DAY,"已退款不得冲回")))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo("DEPENDENCY_EXISTS"));
        consistent();
    }

    private long postedReturn(String manual) {
        var line=new FinanceModels.ReturnLine(null,unit,"4","passed",null,sku,"11.30","0.13",manual,manual==null?null:"旧系统单位成本台账");
        long id=id(returns.create(actor(d.maker),key(),new FinanceModels.ReturnInput(d.customer,warehouse,DAY,"历史销售退回",List.of(line),null,null,"sourceless","历史销售凭据")));
        returns.command(actor(d.maker),id,"submit",key(),new FinanceModels.Version(0L,null,null));
        review("sales_return",id);
        returns.command(actor(d.maker),id,"post",key(),new FinanceModels.Version(version("sales_return",id),null,null));
        return id;
    }

    private void stockOpening() {
        long id=id(opening.createStock(actor(d.maker),key(),new OpeningService.Stock("opening",warehouse,DAY,"真实库存期初",List.of(new OpeningService.StockLine(sku,unit,"10","70")),null)));
        opening.command(actor(d.maker),true,id,"submit",key(),new OpeningService.Version(0L,null));
        opening.command(actor(d.reviewer),true,id,"approve",key(),new OpeningService.Version(1L,null));
        opening.command(actor(d.maker),true,id,"post",key(),new OpeningService.Version(2L,null));
    }

    private void review(String type,long id) {
        var task=jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.id=t.instance_id WHERE i.tenant_id=? AND i.business_type=? AND i.business_id=? AND t.status='pending' AND t.assignee_id=?",d.tenant,type,id,d.reviewer);
        tasks.decide(actor(d.reviewer),number(task.get("id")),"approve",key(),new WorkflowTasks.Decision(number(task.get("version")),"核对数量、成本与资金依据",null,null,null,null,null));
    }
    private CompanyAccess actor(long user) {return new CompanyAccess(d.tenant,user,d.company,true,Set.of(),d.permissions,new OrgScope(List.of(),department));}
    private long version(String table,long id) {
        if(!Set.of("sales_return","refund_request","document_reversal").contains(table))throw new IllegalArgumentException("测试单据类型无效");
        return jdbc.queryForObject("SELECT version FROM "+table+" WHERE tenant_id=? AND id=?",Long.class,d.tenant,id);
    }
    private void dispatch() {
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<deadline) {
            var events=outbox.claim(100,d.tenant);if(events.isEmpty())return;
            for(var event:events)assertThat(delivery.deliver(event)).as(event.type()).isTrue();
        }
        throw new AssertionError("无原单退货总账事件未在测试时限内完成");
    }
    private BigDecimal balance(String account) {
        var amount=jdbc.queryForObject("SELECT COALESCE(SUM(IF(g.direction='D',g.amount,-g.amount)),0) FROM gl_voucher_line g JOIN gl_voucher v ON v.tenant_id=g.tenant_id AND v.company_id=g.company_id AND v.id=g.voucher_id JOIN journal_line l ON l.tenant_id=g.tenant_id AND l.company_id=g.company_id AND l.id=g.journal_line_id WHERE g.tenant_id=? AND v.status IN('posted','reversed') AND l.account_code=?",BigDecimal.class,d.tenant,account);
        if(account.equals("FUNDS"))amount=amount.add(jdbc.queryForObject("SELECT COALESCE(SUM(o.opening_signed),0) FROM gl_opening_balance o JOIN gl_subject s ON s.tenant_id=o.tenant_id AND s.company_id=o.company_id AND s.id=o.subject_id WHERE o.tenant_id=? AND o.company_id=? AND (s.is_cash=1 OR s.is_bank=1)",BigDecimal.class,d.tenant,d.company));
        return amount;
    }
    private void assertBalances(BigDecimal stock,BigDecimal cost,BigDecimal ar) {
        assertThat(balance("INVENTORY")).isEqualByComparingTo(stock);
        assertThat(balance("COGS")).isEqualByComparingTo(cost);
        assertThat(balance("AR")).isEqualByComparingTo(ar);
    }
    private BigDecimal income(String code) {return statementAmount("income_statement",code);}
    @SuppressWarnings("unchecked") private BigDecimal statementAmount(String report,String code) {
        var rows=(List<Map<String,Object>>)financial.statement(actor(d.maker),report,d.november).get("rows");
        return rows.stream().filter(row->code.equals(row.get("code"))).map(row->new BigDecimal(row.get("amount").toString())).findFirst().orElseThrow();
    }
    private void consistent() {
        for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close","gl_trial","gl_operational"))
            assertThat(checks.check(actor(d.maker),type,type.equals("period_close")?d.november:null)).as(type).isEmpty();
        for(String report:List.of("balance_sheet","income_statement","cash_flow"))
            assertThat(financial.statement(actor(d.maker),report,d.november)).containsEntry("passed",true);
    }
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
}
