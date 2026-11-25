package cn.erp.identity;

import cn.erp.ErpApplication;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class AuditQueryIT {
    @Autowired AuditQueryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    long tenant,user,company,visible;
    CompanyAccess actor;
    @BeforeEach void prepare() {
        tenant=Ids.next();user=Ids.next();company=Ids.next();visible=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'审计测试')",tenant,"T"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'C','审计公司')",company,tenant);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'auditor','审计人员','unused-test-hash')",user,tenant);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
        long role=Ids.next();
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'AUDITOR','审计角色')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,operator_id,action,object_type,object_id,request_id,before_data,after_data) VALUES(?,?,?,?,'shipment.sign','shipment',123,'request-1',?,?)",
                visible,tenant,company,user,"{\"status\":\"posted\"}","{\"status\":\"signed\",\"costAmount\":\"12.34\",\"passwordHash\":\"私密密码\",\"nested\":[{\"mobile\":\"私密电话\",\"bankAccountNo\":\"私密账号\",\"qty\":\"9007199254740993\"}]}");
        actor=new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("audit:view"));
    }
    AuditQueryService.Filter filter(String action,Long companyId) {return new AuditQueryService.Filter(action,null,null,null,companyId,null,null,null);}
    @Test void departmentRestrictedAuditorCannotReadWholeDocumentSnapshots() {
        long role=jdbc.queryForObject("SELECT role_id FROM sys_user_role WHERE tenant_id=? AND user_id=?",Long.class,tenant,user);
        assertThat(service.list(actor,filter(null,null),null,20).items()).hasSize(1);
        jdbc.update("INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type) VALUES(?,?,?,'self')",tenant,company,role);
        assertThat(service.list(actor,filter(null,null),null,20).items()).isEmpty();
    }
    @Test void filtersAndPaginationKeepTenantAndFullCompanyScope() {
        jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,action,object_type) VALUES(?,?,?,'shipment.sign','shipment')",Ids.next(),tenant,Ids.next());
        jdbc.update("INSERT INTO audit_log(id,tenant_id,action,object_type) VALUES(?,?,'shipment.sign','shipment')",Ids.next(),Ids.next());
        var result=service.list(actor,filter("shipment.sign",company),null,1);
        assertThat(result.items()).hasSize(1);assertThat(result.items().getFirst().get("id")).isEqualTo(Long.toString(visible));assertThat(result.nextCursor()).isNull();
        assertThat(service.list(actor,filter("payment.confirm",null),null,20).items()).isEmpty();
        long warehouse=Ids.next();jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','审计仓')",warehouse,tenant,company);
        jdbc.update("UPDATE org_user_data_scope SET scope_type='warehouse',warehouse_id=? WHERE tenant_id=? AND user_id=?",warehouse,tenant,user);
        assertThat(service.list(actor,filter(null,null),null,20).items()).isEmpty();
    }
    @Test void recursivelyRedactsFieldPermissionsAndNeverReturnsSecrets() {
        String value=json.writeValueAsString(service.list(actor,filter(null,null),null,20));
        assertThat(value).contains("9007199254740993","signed").doesNotContain("costAmount","私密密码","私密电话","私密账号");
        var fields=new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("audit:view","cost:view","partner_contact:view","cash_account_no:view"));
        assertThat(json.writeValueAsString(service.list(fields,filter(null,null),null,20))).contains("12.34","私密电话","私密账号").doesNotContain("私密密码");
    }
    @Test void validatesFiltersAndResolvesShanghaiDateRange() {
        var today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var date=new AuditQueryService.Filter(null,"shipment",123L,user,company,today,today,"request-1");
        assertThat(service.list(actor,date,null,20).items()).hasSize(1);
        assertThatThrownBy(()->service.list(actor,filter(null,null),"错误",20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.list(actor,new AuditQueryService.Filter(null,null,null,null,null,today,today.plusYears(2),null),null,20)).isInstanceOf(ApiException.class);
    }
    @Test void sourcelessReturnHistoryKeepsBothReviewsAndRedactsWholeCostBasis() {
        var old=Map.of("returnMode","sourceless","costAmount","20.00","lines",List.of(Map.of(
                "skuId","9007199254740993","unitCost","5.00000000","costAmount","20.00",
                "manualUnitCost","0.00000000","manualCostReason","历史成本凭据",
                "costBasis",Map.of("source","average","stockAmount","500.00","stockQuantity","100.000000"))));
        var current=Map.of("returnMode","sourceless","costAmount","24.00","lines",List.of(Map.of(
                "skuId","9007199254740993","costBasis",Map.of("source","average","unitCost","6.00000000","stockAmount","1200.00"))));
        jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,operator_id,action,object_type,object_id,before_data,after_data) VALUES(?,?,?,?,'sales_return.submit','sales_return',456,?,?)",
                Ids.next(),tenant,company,user,json.writeValueAsString(old),json.writeValueAsString(current));
        String hidden=json.writeValueAsString(service.list(actor,filter("sales_return.submit",company),null,20));
        assertThat(hidden).contains("sourceless","9007199254740993").doesNotContain("Cost","cost","stockAmount","stockQuantity","历史成本凭据");
        var reviewer=new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("audit:view","cost:view"));
        String complete=json.writeValueAsString(service.list(reviewer,filter("sales_return.submit",company),null,20));
        assertThat(complete).contains("20.00","24.00","500.00","1200.00","5.00000000","6.00000000","历史成本凭据");
    }
    @Test void financeAuditUsesDomainPermissionsAndDefaultsUnknownFinanceActionsToHidden() {
        var actions=Map.of("gl_config.save","gl_subject","gl_opening.save","gl_account_book","gl_voucher.save","gl_voucher","gl_business.generated","gl_voucher","gl_closing.close","gl_period_close","credit_limit.save","ar_credit_limit","credit_increase.approve","credit_increase","bad_debt.approve","ar_bad_debt","aging_config.save","company","collection.follow","ar_collection_plan");
        for(var entry:actions.entrySet())jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,operator_id,action,object_type,after_data) VALUES(?,?,?,?,?,?,?)",Ids.next(),tenant,company,user,entry.getKey(),entry.getValue(),"{\"amount\":\"123.45\"}");
        jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,action,object_type) VALUES(?,?,?,'gl_future.review','gl_future')",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO audit_log(id,tenant_id,company_id,action,object_type) VALUES(?,?,?,'gl_future.review','company')",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO audit_log(id,tenant_id,action,object_type) VALUES(?,?,'gl_voucher.save','gl_voucher')",Ids.next(),tenant);
        assertThat(service.list(actor,filter(null,null),null,100).items()).hasSize(1);
        var voucherReader=new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("audit:view","cost:view","gl_voucher:view"));
        assertThat(service.list(voucherReader,filter(null,null),null,100).items()).extracting(r->r.get("action")).containsExactlyInAnyOrder("shipment.sign","gl_voucher.save","gl_business.generated");
        var collector=new CompanyAccess(tenant,user,0,true,Set.of(),Set.of("audit:view","collection:view"));
        assertThat(service.list(collector,filter(null,null),null,100).items()).extracting(r->r.get("action")).containsExactlyInAnyOrder("shipment.sign","collection.follow");
        var all=new CompanyAccess(tenant,user,0,true,Set.of(),new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class)));
        assertThat(service.list(all,filter(null,null),null,100).items()).hasSize(11);
        jdbc.update("UPDATE company SET status='disabled' WHERE tenant_id=? AND id=?",tenant,company);
        assertThat(service.list(all,filter(null,company),null,100).items())
            .extracting(r->r.get("action")).containsExactly("shipment.sign");
    }

}
