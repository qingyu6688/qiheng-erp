package cn.erp.reference;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class ReferenceIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PartnerService partners;
    @Autowired CashService cash;
    @Autowired ReferenceService reference;
    @Autowired BusinessCommands commands;
    @Autowired ReferenceAvailability available;
    private CompanyAccess scope;

    @BeforeEach
    void prepare() {
        long tenant=Ids.next(),company=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"T"+tenant,"基础资料测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"A","公司A");
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("partner:view","partner:update","partner_contact:view","cash_account_no:view","cash_account:update"));
    }

    @Test
    void contactsRespectFieldPermissionsAndCannotBeOverwrittenThroughHiddenInputs() {
        var partner=partners.create(scope,new PartnerModels.Partner("P","客户",null,null,Set.of("customer","supplier"),null));
        long id=Long.parseLong(partner.get("id").toString());
        var contact=partners.saveContact(scope,id,null,new PartnerModels.Contact("张先生","13812345678","test@example.com","测试地址",true,null));
        CompanyAccess limited=new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),true,Set.of(),Set.of("partner:view"));
        assertThat(partners.contact(limited,id,Long.parseLong(contact.get("id").toString()))).doesNotContainKeys("mobile","address");
        assertThatThrownBy(()->partners.saveContact(limited,id,null,new PartnerModels.Contact("李先生","123",null,null,false,null))).isInstanceOf(ApiException.class);
        String audit=jdbc.queryForObject("SELECT after_data FROM audit_log WHERE object_type='partner_contact' ORDER BY id DESC LIMIT 1",String.class);
        assertThat(audit).doesNotContain("13812345678","测试地址");
        long contactId=Long.parseLong(contact.get("id").toString());
        partners.deleteContact(scope,id,contactId,0);
        partners.deleteContact(scope,id,contactId,0);
        partners.update(scope,id,new PartnerModels.Partner("P","新客户",null,null,Set.of("supplier"),0L));
        assertThat(partners.get(scope,id,false).get("roles")).isEqualTo(List.of("supplier"));
        assertThatThrownBy(()->partners.update(scope,id,new PartnerModels.Partner("P","客户",null,null,Set.of("customer"),0L)))
                .isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.getStatus()).isEqualTo(409));
        partners.status(scope,id,"disable",1);
        assertThatThrownBy(()->available.requirePartner(scope,id,"supplier"))
                .isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.getErrorCode()).isEqualTo("MASTER_DATA_DISABLED"));
    }

    @Test
    void cashAccountStartsAtZeroAndPreservesMaskedNumberDuringEdit() {
        var account=cash.create(scope,new CashModels.Account("BANK","银行账户","bank","测试银行","622200006411",null));
        long id=Long.parseLong(account.get("id").toString());
        assertThat(account.get("balance")).isEqualTo("0.00");
        CompanyAccess limited=new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),true,Set.of(),Set.of("cash_account:update"));
        assertThat(cash.get(limited,id,false).get("accountNo")).isEqualTo("****6411");
        cash.update(limited,id,new CashModels.Account("BANK","重命名","bank","测试银行",null,0L));
        assertThat(cash.get(scope,id,false).get("accountNo")).isEqualTo("622200006411");
        assertThatThrownBy(()->cash.update(limited,id,new CashModels.Account("BANK","越权","bank",null,"123",1L))).isInstanceOf(ApiException.class);
        cash.update(scope,id,new CashModels.Account("BANK","已移除账号","cash",null,"",1L));
        assertThat(cash.get(scope,id,false).get("accountNo")).isNull();
        CompanyAccess other=new CompanyAccess(Ids.next(),scope.userId(),scope.companyId(),true,Set.of(),Set.of());
        assertThatThrownBy(()->cash.get(other,id,false)).isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.getStatus()).isEqualTo(404));
        cash.status(scope,id,"disable",2);
        assertThatThrownBy(()->available.requireCashAccount(scope,id))
                .isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.getErrorCode()).isEqualTo("MASTER_DATA_DISABLED"));
    }

    @Test
    void periodsAreMonthlySequentialAndItemsHaveVersionedAudit() {
        assertThat(reference.openPeriod(scope,"202611").get("endDate")).isEqualTo("2026-11-30");
        assertThatThrownBy(()->reference.openPeriod(scope,"202613")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reference.openPeriod(scope,"202701")).isInstanceOf(ApiException.class);
        assertThat(reference.openPeriod(scope,"202612").get("status")).isEqualTo("open");
        var item=reference.saveItem(scope,null,new CashModels.Item("FEE","运费","expense","active",null));
        long id=Long.parseLong(item.get("id").toString());
        assertThat(reference.saveItem(scope,id,new CashModels.Item("FEE","运费支出","expense","disabled",0L)).get("version")).isEqualTo(1L);
    }

    @Test
    void commandsReplayOnlyForOriginalActor() {
        String key=UUID.randomUUID().toString();
        String first=commands.execute(scope,"test.create",key,"body",201,()->"result",String.class);
        assertThat(commands.execute(scope,"test.create",key,"body",201,()->"unexpected",String.class)).isEqualTo(first);
        CompanyAccess other=new CompanyAccess(scope.tenantId(),Ids.next(),scope.companyId(),true,Set.of(),Set.of());
        assertThatThrownBy(()->commands.execute(other,"test.create",key,"body",201,()->"leak",String.class))
                .isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.getStatus()).isEqualTo(403));
    }
}
