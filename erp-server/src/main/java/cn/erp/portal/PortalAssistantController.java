package cn.erp.portal;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/portal/assistant")
class PortalAssistantController {
    private static final Duration CONVERSATION_LIFETIME=Duration.ofHours(8);
    private static final Pattern ID=Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern REFUSAL=Pattern.compile("(?:忽略|绕过).*(?:指令|权限|规则)|其他(?:客户|企业|租户)|系统提示|越权|\\b(?:select|update|delete)\\s+",Pattern.CASE_INSENSITIVE);
    private static final Pattern HUMAN=Pattern.compile("人工|投诉|退款|退钱|纠纷|差评");
    private static final DefaultRedisScript<Long> RATE=new DefaultRedisScript<>("""
            local count=redis.call('INCR',KEYS[1]); if count==1 then redis.call('EXPIRE',KEYS[1],60) end; return count
            """,Long.class);
    private static final DefaultRedisScript<Long> RATE_ANSWER=new DefaultRedisScript<>("""
            if redis.call('HGET',KEYS[1],'last') ~= ARGV[1] then return -1 end
            if redis.call('HGET',KEYS[1],'rated') == ARGV[1] then
              local count=tonumber(redis.call('HGET',KEYS[1],'downs') or '0'); if ARGV[2]=='down' and count>=2 then return count end; return -1
            end
            redis.call('HSET',KEYS[1],'rated',ARGV[1]);
            if ARGV[2]=='up' then redis.call('HSET',KEYS[1],'downs','0'); return 0 end
            return redis.call('HINCRBY',KEYS[1],'downs',1)
            """,Long.class);
    private final StringRedisTemplate redis;
    private final PortalSessions sessions;
    private final PortalPurchases purchases;
    private final PortalStatements statements;
    private final PortalTickets tickets;
    private final PortalContentSecurity content;
    private final PortalIntentClient intents;
    private final boolean enabled;
    private final String redisNamespace;

    PortalAssistantController(StringRedisTemplate redis,PortalSessions sessions,PortalPurchases purchases,PortalStatements statements,
                              PortalTickets tickets,PortalContentSecurity content,PortalIntentClient intents,
                              @Value("${ERP_PORTAL_ASSISTANT_ENABLED:false}") boolean enabled,
                              @Value("${APP_ENV:prod}") String environment) {
        this.redis=redis;this.sessions=sessions;this.purchases=purchases;this.statements=statements;
        this.tickets=tickets;this.content=content;this.intents=intents;this.enabled=enabled;
        this.redisNamespace="erp:portal-assistant:"+environment+":";
    }

    record Result(String conversationId,String answerId,String message,String topic,boolean refused,boolean handedOff,
                  String sourcePath,List<?> items,Map<String,Object> summary) {}

    @GetMapping("/status")
    ApiResponse<Map<String,Boolean>> status(HttpServletRequest request) {
        PortalActor.require(request);
        return ApiResponse.success(Map.of("enabled",enabled&&intents.enabled()));
    }

    @PostMapping
    ApiResponse<Result> ask(HttpServletRequest request,@RequestBody JsonNode input) {
        var actor=actor(request);
        if (!input.isObject() || input.size()>(input.has("conversationId")?2:1) || !input.path("text").isString()
                || input.has("conversationId")&&!input.path("conversationId").isString())
            throw invalid();
        String text=input.path("text").asString().strip();
        if(text.isEmpty()||text.length()>1000) throw invalid();
        rate(actor);
        String id=conversation(actor,input.path("conversationId").asString(null));
        String question=content.filter(actor.tenantId(),actor.companyId(),text,"input").text();
        String answer=UUID.randomUUID().toString();
        Result result;
        if(REFUSAL.matcher(text).find()) {
            result=new Result(id,answer,"客服仅能查看当前门户账号的订单、物流、对账和发票。", "",true,false,null,List.of(),Map.of());
        } else {
            String topic=HUMAN.matcher(text).find()?"handoff":intents.topic(actor,question);
            same(request,actor);
            result=reply(actor,id,answer,topic,question,text);
        }
        same(request,actor);
        redis.opsForHash().put(key(actor,id),"last",answer);
        String output=content.filter(actor.tenantId(),actor.companyId(),result.message(),"output").text();
        return ApiResponse.success(new Result(id,answer,output,result.topic(),result.refused(),result.handedOff(),
                result.sourcePath(),result.items(),result.summary()));
    }

    @PostMapping("/feedback")
    ApiResponse<Result> feedback(HttpServletRequest request,@RequestBody JsonNode input) {
        var actor=actor(request);
        if(!input.isObject()||input.size()!=3) throw invalid();
        String id=existing(input.path("conversationId").asString(""));
        String answer=existing(input.path("answerId").asString(""));
        String vote=input.path("vote").asString("");
        if(!"up".equals(vote)&&!"down".equals(vote)) throw invalid();
        rate(actor);
        Long count=redis.execute(RATE_ANSWER,List.of(key(actor,id)),answer,vote);
        if(count==null||count<0) throw new ApiException(409,"PORTAL_FEEDBACK_INVALID","此回复无法再次评价，请打开当前回复");
        if(count>=2) {
            same(request,actor);
            return ApiResponse.success(handoff(actor,id,answer,"other","客户连续两次表示回复未解决问题。"));
        }
        return ApiResponse.success(new Result(id,answer,"已记录反馈。","",false,false,null,List.of(),Map.of()));
    }

    private Result reply(PortalActor actor,String id,String answer,String topic,String text,String original) {
        return switch(topic) {
            case "orders" -> new Result(id,answer,"以下是近期订单，详情页可核对完整履约进度。",topic,false,false,"/orders",
                    purchases.orders(actor,null,3).stream().limit(3).toList(),Map.of());
            case "shipments" -> new Result(id,answer,"以下是近期发货，详情页可核对物流与签收状态。",topic,false,false,"/shipments",
                    purchases.shipments(actor,null,3).stream().limit(3).toList(),Map.of());
            case "invoices" -> new Result(id,answer,"以下是近期发票，请从发票页下载版式文件。",topic,false,false,"/invoices",
                    purchases.invoices(actor,null,3).stream().limit(3).toList(),Map.of());
            case "statements" -> {
                var day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
                var total=statements.get(actor,day.withDayOfMonth(1),day).summary();
                yield new Result(id,answer,"这是本月对账摘要，详情页可选择其他期间。",topic,false,false,"/statements",List.of(),
                        Map.of("openingBalance",total.openingBalance(),"closingBalance",total.closingBalance()));
            }
            default -> handoff(actor,id,answer,original.contains("投诉")?"complaint":original.contains("退款")?"refund":"other",text);
        };
    }

    private Result handoff(PortalActor actor,String id,String answer,String category,String text) {
        var ticket=tickets.handoff(actor,id,category,"门户在线客服转人工",text);
        return new Result(id,answer,"已转交人工客服，可在工单中补充问题。","handoff",false,true,"/tickets",List.of(),
                Map.of("ticketId",ticket.ticket().id()));
    }

    private PortalActor actor(HttpServletRequest request) {
        if(!enabled||!intents.enabled()) throw new ApiException(404,"PORTAL_ASSISTANT_DISABLED","在线客服暂未开放");
        return PortalActor.require(request);
    }

    private void same(HttpServletRequest request,PortalActor actor) {
        if(!Objects.equals(actor,sessions.authenticate(request))) throw PortalActor.expired();
    }

    private String prefix(PortalActor actor) {
        return redisNamespace+actor.tenantId()+":"+actor.companyId()+":"+actor.userId()+":";
    }

    private String key(PortalActor actor,String id) {return prefix(actor)+id;}

    private String conversation(PortalActor actor,String supplied) {
        if(supplied!=null) {
            String id=existing(supplied);
            if(!Boolean.TRUE.equals(redis.hasKey(key(actor,id)))) throw new ApiException(404,"NOT_FOUND","会话不存在或已过期");
            return id;
        }
        String id=UUID.randomUUID().toString();
        redis.opsForHash().put(key(actor,id),"last","");
        redis.expire(key(actor,id),CONVERSATION_LIFETIME);
        return id;
    }

    private static String existing(String id) {
        if(!ID.matcher(id).matches()) throw invalid();
        return id;
    }

    private void rate(PortalActor actor) {
        Long count=redis.execute(RATE,List.of(prefix(actor)+"rate"));
        if(count==null||count>12) throw new ApiException(429,"PORTAL_ASSISTANT_RATE","在线客服请求过于频繁，请稍后重试");
    }

    private static ApiException invalid() {return new ApiException(400,"VALIDATION_FAILED","请检查客服请求内容");}

}
