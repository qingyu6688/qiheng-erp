package cn.erp.reference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CashDefaultsIT {
    @Autowired CashService cash;
    @Autowired JdbcTemplate jdbc;
    private final long tenant=Ids.next(), company=Ids.next();
    private final CompanyAccess actor=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),
            Set.of("cash_account:update","cash_account_no:view"));

    @BeforeEach
    void prepare() {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'默认账户测试')",tenant,"DEFAULT"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','默认账户公司')",company,tenant);
    }

    @AfterEach
    void clean() {
        for (String table:List.of("audit_log","cash_account_balance","cash_account","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void m10X0501DefaultsAreIndependentVersionedAndClearedWhenDisabled() {
        long first=create("FIRST"),second=create("SECOND");
        assertThat(cash.get(actor,first,false).get("defaultForReceipt")).isEqualTo(false);
        cash.setDefault(actor,first,new CashModels.DefaultAccount("receipt",0L));
        cash.setDefault(actor,second,new CashModels.DefaultAccount("receipt",0L));
        assertThat(cash.get(actor,first,false).get("defaultForReceipt")).isEqualTo(false);
        assertThat(cash.get(actor,second,false).get("defaultForReceipt")).isEqualTo(true);
        cash.setDefault(actor,first,new CashModels.DefaultAccount("payment",2L));
        assertThat(cash.get(actor,first,false).get("defaultForPayment")).isEqualTo(true);
        cash.status(actor,first,"disable",3L);
        assertThat(cash.get(actor,first,false).get("defaultForPayment")).isEqualTo(false);
        assertThatThrownBy(()->cash.setDefault(actor,first,new CashModels.DefaultAccount("payment",4L)))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(422));
        assertThatThrownBy(()->cash.setDefault(actor,second,new CashModels.DefaultAccount("receipt",0L)))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(409));
        String audit=jdbc.queryForObject("SELECT after_data FROM audit_log WHERE tenant_id=? AND action='cash_account.set_default' ORDER BY id DESC LIMIT 1",String.class,tenant);
        assertThat(audit).doesNotContain("accountNo");
    }

    @Test
    void m10X0501ConcurrentSwitchesKeepExactlyOneDefault() throws Exception {
        long first=create("FIRST"),second=create("SECOND");
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=List.of(first,second).stream().map(id->executor.submit(()->{
                ready.countDown();
                if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("并发测试未启动");
                return cash.setDefault(actor,id,new CashModels.DefaultAccount("receipt",0L));
            })).toList();
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task:tasks) assertThat(task.get(15,TimeUnit.SECONDS)).containsEntry("defaultForReceipt",true);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_account WHERE tenant_id=? AND company_id=? AND default_for_receipt=1",Long.class,tenant,company)).isEqualTo(1);
        long remaining=jdbc.queryForObject("SELECT id FROM cash_account WHERE tenant_id=? AND default_for_receipt=0",Long.class,tenant);
        assertThatThrownBy(()->jdbc.update("UPDATE cash_account SET default_for_receipt=1 WHERE tenant_id=? AND id=?",tenant,remaining))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void m10X0501RejectsMissingPermissionAndOtherCompany() {
        long id=create("FIRST");
        var limited=new CompanyAccess(tenant,actor.userId(),company,true,Set.of(),Set.of());
        assertThatThrownBy(()->cash.setDefault(limited,id,new CashModels.DefaultAccount("receipt",0L)))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(403));
        long other=Ids.next();
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','其他公司')",other,tenant);
        var otherScope=new CompanyAccess(tenant,actor.userId(),other,true,Set.of(),actor.permissions());
        assertThatThrownBy(()->cash.setDefault(otherScope,id,new CashModels.DefaultAccount("receipt",0L)))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
    }

    private long create(String code) {
        return Long.parseLong(cash.create(actor,new CashModels.Account(code,code,"bank",null,null,null)).get("id").toString());
    }
}
