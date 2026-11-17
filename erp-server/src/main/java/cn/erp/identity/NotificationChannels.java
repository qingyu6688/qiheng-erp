package cn.erp.identity;

import cn.erp.shared.ApiException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 业务消息复用后台渠道凭据和已验证绑定，短信使用自己的审核模板。 */
@Service
public class NotificationChannels {
    private final LoginSettingsService settings;private final LoginCodeDelivery delivery;private final JdbcTemplate jdbc;
    public NotificationChannels(LoginSettingsService settings,LoginCodeDelivery delivery,JdbcTemplate jdbc) {this.settings=settings;this.delivery=delivery;this.jdbc=jdbc;}
    public String destination(long tenant,long user,String channel) {return jdbc.queryForList("SELECT i.subject FROM user_login_identity i JOIN sys_user u ON u.tenant_id=i.tenant_id AND u.id=i.user_id AND u.status='active' WHERE i.tenant_id=? AND i.user_id=? AND i.method=?",String.class,tenant,user,channel).stream().findFirst().orElse(null);}
    public void send(long tenant,String channel,String destination,String title,String content,String template,Map<String,String> parameters) {
        var provider=settings.provider(tenant,channel);if(provider==null) throw new ApiException(503,"CHANNEL_UNAVAILABLE","消息渠道未启用或配置不完整，请检查后台渠道设置");
        switch(channel) {case "email"->delivery.sendBusinessEmail(provider,destination,title,content);case "sms"->{if(template==null||!template.matches("SMS_[A-Za-z0-9]{1,60}")) throw new ApiException(422,"TEMPLATE_INVALID","请选择已审核的短信业务模板");delivery.sendBusinessSms(provider,destination,template,parameters);}default->throw new ApiException(400,"VALIDATION_FAILED","消息渠道无效");}
    }
    public void sendPortalLoginCode(long tenant, String destination, String code) {
        var provider = settings.provider(tenant,"sms");
        if (provider == null) throw new ApiException(503,"CHANNEL_UNAVAILABLE","短信验证码渠道未启用");
        delivery.send("sms",provider,destination,code);
    }
    public static String mask(String value) {if(value==null||value.length()<5) return "未绑定";int at=value.indexOf('@');String masked=at>0?value.substring(0,1)+"***"+value.substring(at):value.substring(0,3)+"****"+value.substring(value.length()-4);return masked.length()>100?masked.substring(0,80)+"…"+masked.substring(masked.length()-12):masked;}
}
