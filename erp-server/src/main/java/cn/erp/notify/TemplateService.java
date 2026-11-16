package cn.erp.notify;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.regex.Matcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.notify.NoticeService.*;

@Service
public class TemplateService {
    private static final java.util.regex.Pattern VARIABLE=java.util.regex.Pattern.compile("\\{([a-z][a-zA-Z0-9_]{0,31})}");
    public record Input(@NotBlank(message="请填写模板编码") @Pattern(regexp="[a-z][a-z0-9_.-]{0,63}",message="模板编码无效") String code,@NotBlank(message="请选择模板渠道") @Pattern(regexp="site|email|sms",message="模板渠道无效") String channel,@NotBlank(message="请填写标题") @Size(max=200,message="标题最多200字") String title,@NotBlank(message="请填写内容") @Size(max=4000,message="内容最多4000字") String content,@NotNull(message="请填写参数白名单") @Size(max=20,message="参数最多20项") List<String> params,@Size(max=64,message="短信模板编号过长") String smsTemplateId,@NotBlank(message="请选择模板状态") @Pattern(regexp="active|disabled",message="模板状态无效") String status,@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    public record Rendered(String title,String content,String smsTemplateId,Map<String,String> parameters) {}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final BusinessCommands commands;private final AuditService audit;
    public TemplateService(JdbcTemplate jdbc,ObjectMapper json,BusinessCommands commands,AuditService audit) {this.jdbc=jdbc;this.json=json;this.commands=commands;this.audit=audit;}
    public Map<String,Object> list(CompanyAccess actor,Long cursor,int limit) {PlatformAccess.require(jdbc,actor,"notify_template:view");return page(jdbc.queryForList("SELECT * FROM notify_template WHERE tenant_id IN(0,?) AND id<? ORDER BY id DESC LIMIT ?",actor.tenantId(),before(cursor,limit),limit+1),limit);}
    @Transactional public Map<String,Object> save(CompanyAccess actor,Long id,String key,Input input) {
        PlatformAccess.lock(jdbc,actor,id==null?"notify_template:create":"notify_template:update");
        if(input.title().chars().anyMatch(Character::isISOControl)) throw invalid("模板标题不能含换行或控制字符");
        if(input.params().stream().anyMatch(p->p==null||!p.matches("[a-z][a-zA-Z0-9_]{0,31}"))||new HashSet<>(input.params()).size()!=input.params().size()) throw invalid("参数白名单格式有误或重复");
        var supported=Map.of("business.todo",Set.of("title"),"file.import.finished",Set.of(),"file.export.finished",Set.of(),"notice.published",Set.of("title"),"system.alert",Set.of("message"),"workflow.todo",Set.of("title"),"workflow.remind",Set.of("title"),"workflow.copy",Set.of("title"),"workflow.done",Set.of("title"));
        if(supported.containsKey(input.code())&&!supported.get(input.code()).containsAll(input.params())) throw invalid("该系统消息仅支持规定的变量，请使用标题title或提醒message等对应参数");
        for(String text:List.of(input.title(),input.content())) {var matcher=VARIABLE.matcher(text);while(matcher.find()) if(!input.params().contains(matcher.group(1))) throw invalid("模板变量必须在白名单中声明");if(text.replaceAll(VARIABLE.pattern(),"").contains("{")||text.replaceAll(VARIABLE.pattern(),"").contains("}")) throw invalid("模板只支持简单的{变量}，不能使用表达式");}
        if(input.channel().equals("sms")&&(input.smsTemplateId()==null||!input.smsTemplateId().matches("SMS_[A-Za-z0-9]{1,60}"))) throw invalid("短信模板须填写已审核的SMS模板编号");
        return commands.execute(actor,"notify_template.save",key,Arrays.asList(id,input),200,()->{
            long current=id==null?Ids.next():id;
            if(id!=null) {var rows=jdbc.queryForList("SELECT * FROM notify_template WHERE tenant_id=? AND id=? FOR UPDATE",actor.tenantId(),id);if(rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","模板不存在或为只读预置模板");version(input.version(),rows.getFirst().get("version"));if(!rows.getFirst().get("code").equals(input.code())||!rows.getFirst().get("channel").equals(input.channel())) throw invalid("模板编码和渠道不能修改");}
            if(id==null) jdbc.update("INSERT INTO notify_template(id,tenant_id,code,channel,title,content,params,sms_template_id,status,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",current,actor.tenantId(),input.code(),input.channel(),input.title(),input.content(),json.writeValueAsString(input.params()),input.smsTemplateId(),input.status(),actor.userId(),actor.userId());
            else jdbc.update("UPDATE notify_template SET title=?,content=?,params=?,sms_template_id=?,status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",input.title(),input.content(),json.writeValueAsString(input.params()),input.smsTemplateId(),input.status(),actor.userId(),actor.tenantId(),id);
            audit.record(actor,"notify_template.save","notify_template",current,null,Map.of("code",input.code(),"channel",input.channel()));return ApiRows.row(jdbc.queryForMap("SELECT * FROM notify_template WHERE tenant_id=? AND id=?",actor.tenantId(),current));
        },Map.class);
    }
    public Rendered render(long tenant,String code,String channel,Map<String,String> parameters) {
        var rows=jdbc.queryForList("SELECT * FROM notify_template WHERE tenant_id IN(0,?) AND code=? AND channel=? ORDER BY tenant_id DESC LIMIT 1",tenant,code,channel);if(rows.isEmpty()||!rows.getFirst().get("status").equals("active")) return null;
        var row=rows.getFirst();List<?> allowed=json.readValue(row.get("params").toString(),List.class);var used=new LinkedHashMap<String,String>();
        for(Object parameter:allowed) {String name=parameter.toString();if(!parameters.containsKey(name)||parameters.get(name).length()>1000) throw invalid("模板缺少声明的参数或参数过长");used.put(name,parameters.get(name));}
        String title=replace(row.get("title").toString(),used);if(title.chars().anyMatch(Character::isISOControl)) throw invalid("渲染后的标题不能含换行或控制字符");
        return new Rendered(bounded(title,200),bounded(replace(row.get("content").toString(),used),4000),Objects.toString(row.get("sms_template_id"),null),Map.copyOf(used));
    }
    private static String bounded(String value,int max) {return value.codePointCount(0,value.length())>max?value.substring(0,value.offsetByCodePoints(0,max)):value;}
    private static String replace(String template,Map<String,String> parameters) {var result=new StringBuilder();var matcher=VARIABLE.matcher(template);while(matcher.find()) matcher.appendReplacement(result,Matcher.quoteReplacement(parameters.get(matcher.group(1))));matcher.appendTail(result);return result.toString();}
}
