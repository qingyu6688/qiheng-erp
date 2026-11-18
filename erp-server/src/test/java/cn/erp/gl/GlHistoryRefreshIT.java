package cn.erp.gl;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** 对同一实际账套按期间全量重建，再回滚；证据按业务键和会计值取摘要。 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="ERP_O30_REFRESH",matches="1")
class GlHistoryRefreshIT {
    @Autowired JdbcTemplate db;
    @Autowired GlBalances balances;
    @Autowired AccessService access;
    @Autowired GlQueryService queries;
    @Autowired TransactionTemplate transaction;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) throws Exception {
        GlHistoryCapacityIT.requireIsolatedEnvironment(registry);
    }

    @Test void twelveStatementAndBalanceSnapshotsMatch() throws Exception {
        String tenantCode="LOAD-"+GlHistoryCapacityIT.env("ERP_O30_RUN")+"-0";
        long tenant=db.queryForObject("SELECT id FROM tenant WHERE code=?",Long.class,tenantCode);
        long company=db.queryForObject("SELECT id FROM company WHERE tenant_id=? AND code='C0'",Long.class,tenant);
        long user=db.queryForObject("SELECT id FROM sys_user WHERE tenant_id=? AND username='reviewer'",Long.class,tenant);
        CompanyAccess actor=access.forUser(tenant,user,company);
        assertThat(actor).as("须能按实际授权取得本公司的复核人身份").isNotNull();
        YearMonth first=YearMonth.parse(GlHistoryCapacityIT.env("ERP_O30_START"));
        var periods=new ArrayList<Map<String,Object>>();
        for(int index=0; index<12; index++) {
            String month=first.plusMonths(index).format(DateTimeFormatter.ofPattern("yyyyMM"));
            long id=db.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND period=?",
                    Long.class,tenant,company,month);
            long voucherCount=db.queryForObject("SELECT COUNT(*) FROM gl_voucher WHERE tenant_id=? AND company_id=? AND period_id=? AND source='business' AND status='posted'",Long.class,tenant,company,id);
            assertThat(voucherCount).as("每期都需已有实际过账凭证").isPositive();
            var originalIds=balanceIds(actor,id);
            var compared=transaction.execute(state->{
                var before=balanceRows(actor,id);
                assertThat(before).as("余额行不能为空").isNotEmpty();
                var priorReports=reports(actor,id);
                balances.refresh(actor,id);
                var rebuilt=balanceRows(actor,id);
                var nextReports=reports(actor,id);
                assertThat(rebuilt).as("逐行比较期间 %s 科目、七种辅助维度与会计数值",month).isEqualTo(before);
                assertThat(nextReports).as("逐行比较期间 %s 三大报表及勾稽结果",month).isEqualTo(priorReports);
                assertThat(balanceIds(actor,id)).as("确认确实重建过余额实体").isNotEqualTo(originalIds);
                state.setRollbackOnly();
                var data=new TreeMap<String,Object>();
                data.put("period",month); data.put("businessVouchers",voucherCount); data.put("balanceRows",before.size());
                data.put("balanceBefore",digest(before)); data.put("balanceAfter",digest(rebuilt));
                var hashes=new TreeMap<String,Object>();
                for(var kind:priorReports.keySet()){
                    var report=priorReports.get(kind);
                    hashes.put(kind,Map.of("before",digest(report),"after",digest(nextReports.get(kind)),
                            "rows",((List<?>)report.get("rows")).size(),"passed",report.get("passed")));
                }
                data.put("statements",hashes);
                return data;
            });
            assertThat(balanceIds(actor,id)).as("回滚后余额实体与原库一致").isEqualTo(originalIds);
            periods.add(compared);
        }
        Path output=Path.of(GlHistoryCapacityIT.env("ERP_O30_REFRESH_RESULT"));
        if(!output.isAbsolute() || !GlHistoryCapacityIT.real(output.getParent().toString()).startsWith(GlHistoryCapacityIT.inside("ERP_TEST_ROOT")))
            throw new IllegalArgumentException("刷新校验证据须位于本轮测试根目录");
        Files.writeString(output,json.writeValueAsString(Map.of("tenantCode",tenantCode,"companyId",String.valueOf(company),
                "periods",periods,"statementComparisons",periods.size()*3,"rolledBack",true)),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    List<Long> balanceIds(CompanyAccess actor,long period){
        return db.queryForList("SELECT id FROM gl_balance WHERE tenant_id=? AND company_id=? AND period_id=? ORDER BY id",
                Long.class,actor.tenantId(),actor.companyId(),period);
    }

    List<Map<String,Object>> balanceRows(CompanyAccess a,long p){
        return db.queryForList("""
            SELECT period_id,subject_id,aux_customer_id,aux_supplier_id,aux_dept_id,aux_employee_id,aux_project_id,
                aux_sku_id,aux_custom_id,opening_signed,debit,credit,opening_quantity,quantity_debit,quantity_credit,
                opening_debit,opening_credit,closing_debit,closing_credit
            FROM gl_balance WHERE tenant_id=? AND company_id=? AND period_id=?
            ORDER BY subject_id,aux_customer_id,aux_supplier_id,aux_dept_id,aux_employee_id,aux_project_id,aux_sku_id,aux_custom_id
            """,a.tenantId(),a.companyId(),p);
    }

    Map<String,Map<String,Object>> reports(CompanyAccess a,long period){
        var result=new TreeMap<String,Map<String,Object>>();
        for(String kind:List.of("balance_sheet","income_statement","cash_flow")){
            var report=queries.statement(a,kind,period);
            assertThat((List<?>)report.get("rows")).isNotEmpty();
            assertThat(report.get("passed")).as("报表自身勾稽检查须通过").isEqualTo(true);
            result.put(kind,report);
        }
        return result;
    }

    Object canonical(Object value){
        if(value instanceof Map<?,?> row){
            var ordered=new TreeMap<String,Object>();
            row.forEach((k,v)->ordered.put(String.valueOf(k),canonical(v)));
            return ordered;
        }
        if(value instanceof List<?> rows) return rows.stream().map(this::canonical).toList();
        return value;
    }

    String digest(Object value){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    json.writeValueAsString(canonical(value)).getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException impossible){ throw new IllegalStateException("缺少 SHA-256",impossible); }
    }
}
