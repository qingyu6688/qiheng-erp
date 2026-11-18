package cn.erp.sysadmin;

import cn.erp.identity.*;
import cn.erp.notify.*;
import cn.erp.shared.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.*;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="ERP_LOGIN_SETTINGS_KEY=9MPE+YdNGv8bC9K/9OWeYl5K0tqY7MSqwxW8gWsPmpU=")
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class PlatformAdministrationIT {
    @Autowired JdbcTemplate jdbc;@Autowired DictionaryService dictionaries;@Autowired SystemConfiguration configs;@Autowired AreaService areas;@Autowired NoticeService notices;@Autowired MessageService messages;@Autowired RobotSettings robots;
    CompanyAccess actor,other;
    @BeforeEach void prepare() {
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(89001,70101,'platform','管理员','非登录测试摘要'),(89002,70101,'recipient','收件人','非登录测试摘要')");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(89011,70101,'TEST_ADMIN','测试管理')");
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 89000+id,70101,89011,id FROM sys_permission");
        for(long user:List.of(89001L,89002L)) {jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,70101,?,89011)",Ids.next(),user);jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,70101,?,'company',70201)",Ids.next(),user);}
        var permissions=Set.copyOf(jdbc.queryForList("SELECT code FROM sys_permission",String.class));actor=new CompanyAccess(70101,89001,70201,true,Set.of(),permissions);other=new CompanyAccess(70101,89002,70201,true,Set.of(),permissions);
    }
    @Test void Y03_01_presetsCannotBeChangedButTenantDisablePreservesHistoricalLabels() {
        var before=dictionaries.dictionaries(actor,"customer_source",null);assertThat(before.toString()).contains("referral");
        var preset=jdbc.queryForMap("SELECT * FROM sys_dict_item WHERE id=1");
        var input=new DictionaryService.ItemInput("customer_source","referral",preset.get("label").toString(),preset.get("color").toString(),((Number)preset.get("sort_no")).intValue(),"disabled",0L);
        dictionaries.saveItem(actor,1L,key(),input);
        assertThat(dictionaries.dictionaries(actor,"customer_source",before.get("version").toString())).containsEntry("unchanged",false);
        assertThatThrownBy(()->dictionaries.validateValue(70101,"customer_source","referral",false)).isInstanceOf(ApiException.class);dictionaries.validateValue(70101,"customer_source","referral",true);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_dict_item WHERE id=1",String.class)).isEqualTo("active");
        var otherTenant=new CompanyAccess(70102,89002,70203,true,Set.of(),actor.permissions());assertThat(dictionaries.dictionaries(otherTenant,"customer_source",null).toString()).contains("enabled=1");
        assertThatThrownBy(()->dictionaries.saveType(actor,null,key(),new DictionaryService.TypeInput("shipment_status","签收状态","active",0L))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->dictionaries.delete(actor,false,1,0,key())).isInstanceOf(ApiException.class);
    }
    @Test void Y03_02_referencedItemsCannotBeDeletedAndRestrictedAdministratorIsDenied() {
        var item=dictionaries.saveItem(actor,null,key(),new DictionaryService.ItemInput("customer_source","expo2","展会二","primary",50,"active",0L));long id=Long.parseLong(item.get("id").toString());
        jdbc.update("UPDATE business_partner SET source_code='expo2' WHERE id=70401");assertThatThrownBy(()->dictionaries.delete(actor,false,id,0,key())).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE business_partner SET source_code=NULL WHERE id=70401");dictionaries.delete(actor,false,id,0,key());
        jdbc.update("INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type) VALUES(70101,70201,89011,'self')");
        assertThatThrownBy(()->dictionaries.types(actor,"",null,50)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThat(dictionaries.dictionaries(actor,"customer_source",null)).containsKey("items");
    }
    @Test void Z01_01_sensitiveValuesAreEncryptedAndNeverReturnedOrAudited() {
        String secret="平台敏感值"+key();var input=new SystemConfiguration.Input(secret,"string",true,"测试说明",0L);var result=configs.save(actor,"custom.secret",key(),input);
        assertThat(result).containsEntry("value","").containsEntry("secretConfigured",true);assertThat(configs.get(70101,"custom.secret","")).isEqualTo(secret);
        assertThat(configs.list(actor,"").toString()).doesNotContain(secret);assertThat(jdbc.queryForObject("SELECT config_value FROM sys_config WHERE tenant_id=70101 AND config_key='custom.secret'",String.class)).doesNotContain(secret);
        assertThat(jdbc.queryForList("SELECT after_data FROM audit_log WHERE tenant_id=70101").toString()).doesNotContain(secret);
        assertThatThrownBy(()->configs.save(actor,"auth.permission",key(),input)).isInstanceOf(ApiException.class);assertThatThrownBy(()->configs.save(actor,"notify.wecom",key(),input)).isInstanceOf(ApiException.class);
        configs.save(actor,"page.size",key(),new SystemConfiguration.Input("20","number",false,"分页",0L));assertThat(configs.get(70101,"page.size","0")).isEqualTo("20");configs.save(actor,"page.size",key(),new SystemConfiguration.Input("30","number",false,"分页",1L));assertThat(configs.get(70101,"page.size","0")).isEqualTo("30");
    }
    @Test void Y08_01_officialAreasHaveVersionAndRejectFabricatedCode() {
        assertThat(areas.tree()).containsEntry("version","MCA-2024");assertThat(areas.tree().get("items")).asList().hasSize(34);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_area",Integer.class)).isEqualTo(3213);areas.validate(110101L);assertThatThrownBy(()->areas.validate(999999L)).isInstanceOf(ApiException.class);
    }
    @Test void Y04_01_noticeSanitizesHtmlChecksAudienceAndWithdrawsUnreadMessages() {
        var notice=notices.save(actor,null,key(),new NoticeService.Input("重要公告","<p>正文<script>窃取()</script><a href='javascript:alert(1)'>链接</a></p>","important",null,null,List.of(new NoticeService.Target("user",89002)),0L));long id=Long.parseLong(notice.get("id").toString());
        assertThat(notice.get("content").toString()).doesNotContain("script","javascript");notices.change(actor,id,"publish",0,key());assertThat(messages.important(other)).hasSize(1);assertThat(messages.important(actor)).isEmpty();
        assertThat(messages.summary(other).get("announcements")).asList().singleElement().asString()
                .contains("id="+id,"title=重要公告","level=important").doesNotContain("content=");
        assertThat(messages.summary(actor).get("announcements")).asList().isEmpty();
        assertThatThrownBy(()->messages.notice(actor,id)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        var inbox=messages.list(other,"notice",true,null,50);assertThat(inbox.get("items")).asList().hasSize(1);long messageId=jdbc.queryForObject("SELECT id FROM notify_message WHERE notice_id=? AND user_id=89002",Long.class,id);
        messages.read(other,messageId);assertThat(messages.important(other)).isEmpty();assertThatThrownBy(()->messages.read(actor,messageId)).isInstanceOf(ApiException.class);
        notices.change(actor,id,"withdraw",1,key());assertThat(messages.list(other,"notice",null,null,50).get("items")).asList().isEmpty();assertThatThrownBy(()->messages.notice(other,id)).isInstanceOf(ApiException.class);
        assertThat(messages.summary(other).get("announcements")).asList().isEmpty();
    }
    @Test void Y05_01_todoAndCountsRespectDepartmentWarehouseCompanyAndRevocation() {
        jdbc.update("UPDATE sales_order SET status='pending',owner_department_id=10,owner_user_id=89001,created_by=89001 WHERE id=71201");
        assertThat(messages.summary(other).get("todos").toString()).contains("71201");assertThat(messages.counts(other).get("total")).isEqualTo(1L);messages.sync(other);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message WHERE tenant_id=70101 AND user_id=89002",Integer.class)).isEqualTo(1);
        var denied=new CompanyAccess(70101,89002,70201,true,Set.of(),actor.permissions(),OrgScope.restricted(Set.of(),Set.of(),0));assertThat(messages.counts(denied).get("total")).isEqualTo(0L);
        var wh=new CompanyAccess(70101,89002,70201,false,Set.of(70302L),actor.permissions());assertThat(messages.list(wh,"todo",null,null,50).get("items")).asList().isEmpty();
        long id=jdbc.queryForObject("SELECT id FROM notify_message WHERE tenant_id=70101 AND user_id=89002",Long.class);assertThatThrownBy(()->messages.read(denied,id)).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE sales_order SET status='draft' WHERE id=71201");assertThat(messages.counts(other).get("total")).isEqualTo(0L);
    }
    @Test void Y06_01_robotRequiresDedicatedPermissionAndKeepsSecretOnBlankUpdate() {
        String secret="mock-wecom-key-12345678";var result=robots.save(actor,key(),new RobotSettings.Input(true,secret,0L));assertThat(result.toString()).doesNotContain(secret);assertThat(robots.current(70101).key()).isEqualTo(secret);
        robots.save(actor,key(),new RobotSettings.Input(false,"",1L));assertThat(robots.current(70101).key()).isEqualTo(secret);
        var viewer=new CompanyAccess(70101,89002,70201,true,Set.of(),Set.of("sys_config:update"));assertThatThrownBy(()->robots.save(viewer,key(),new RobotSettings.Input(true,secret,2L))).isInstanceOf(ApiException.class);
    }
    String key() {return UUID.randomUUID().toString();}
}
