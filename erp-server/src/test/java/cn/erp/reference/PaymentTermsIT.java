package cn.erp.reference;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.PaymentTerms;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class PaymentTermsIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PartnerService partners;
    private CompanyAccess actor;

    @BeforeEach void seed() {
        long tenant=Ids.next(),company=Ids.next(),user=Ids.next();
        actor=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of("partner:update"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'账期测试')",tenant,"TERM"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司')",company,tenant);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'terms','账期验收','验收占位')",user,tenant);
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id,default_term_type,default_term_value) VALUES(?,?,?,'days',30)",Ids.next(),tenant,company);
    }

    @Test void partnerRolesHaveIndependentTermsAndOmittedUpdatesPreserveThem() {
        var input=new PartnerModels.Partner("P","往来单位",null,null,Set.of("customer","supplier"),null,null,null,null,
                Map.of("customer",new PaymentTerms.Term("days",0),"supplier",new PaymentTerms.Term("monthly",28)));
        var created=partners.create(actor,input);long partner=Long.parseLong(created.get("id").toString());
        assertThat(PaymentTerms.resolve(jdbc,actor,partner,"customer")).isEqualTo(new PaymentTerms.Term("days",0));
        assertThat(PaymentTerms.resolve(jdbc,actor,partner,"supplier")).isEqualTo(new PaymentTerms.Term("monthly",28));
        partners.update(actor,partner,new PartnerModels.Partner("P","更名",null,null,Set.of("customer","supplier"),0L));
        assertThat(PaymentTerms.resolve(jdbc,actor,partner,"supplier")).isEqualTo(new PaymentTerms.Term("monthly",28));
    }

    @Test void emptyRoleUsesCompanyDefaultAndSnapshotSurvivesLaterPolicyChanges() {
        var created=partners.create(actor,new PartnerModels.Partner("P","往来单位",null,null,Set.of("customer"),null));
        long partner=Long.parseLong(created.get("id").toString()),order=Ids.next();
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive) VALUES(?,?,?,'TERM-1',?,'2026-11-23',1)",
                order,actor.tenantId(),actor.companyId(),partner);
        PaymentTerms.snapshot(jdbc,actor,"sales_order",order,partner,"customer");
        jdbc.update("UPDATE company_policy SET default_term_value=60 WHERE tenant_id=? AND company_id=?",actor.tenantId(),actor.companyId());
        assertThat(PaymentTerms.forOrder(jdbc,actor,order,"customer",LocalDate.of(2026,11,23)))
                .isEqualTo(LocalDate.of(2026,12,23));
        assertThat(PaymentTerms.resolve(jdbc,actor,partner,"customer")).isEqualTo(new PaymentTerms.Term("days",60));
    }

    @Test void rejectsTermsForUnselectedRole() {
        var input=new PartnerModels.Partner("P","往来单位",null,null,Set.of("customer"),null,null,null,null,
                Map.of("supplier",new PaymentTerms.Term("days",10)));
        assertThatThrownBy(()->partners.create(actor,input)).isInstanceOf(ApiException.class);
    }

    @Test void migrationRejectsHalfEmptyAndInvalidMonthlyTerms() {
        assertThatThrownBy(()->jdbc.update("UPDATE company_policy SET default_term_type=NULL,default_term_value=30 WHERE tenant_id=?",actor.tenantId()))
                .isInstanceOfSatisfying(UncategorizedSQLException.class,error->assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(()->jdbc.update("UPDATE company_policy SET default_term_type='monthly',default_term_value=29 WHERE tenant_id=?",actor.tenantId()))
                .isInstanceOfSatisfying(UncategorizedSQLException.class,error->assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819));
    }
}
