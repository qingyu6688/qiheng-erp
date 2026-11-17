package cn.erp.ai.assistant;

import java.sql.Statement;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiInputs;

@Repository
class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ConversationStore(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

    record Conversation(String id,String assistantCode,String title,String createdAt) {}
    record Receipt(String id,String fingerprint) {}
    record Stored(String id,String role,String content,List<AssistantRuntime.Citation> citations,List<Receipt> receipts,
                  List<AssistantRuntime.ToolResult> tools,String createdAt) {}

    List<Conversation> list(AiActor actor) {
        return jdbc.query("""
            SELECT id,assistant_code,title,created_at FROM ai_conversation
            WHERE tenant_id=? AND company_id=? AND user_id=? AND updated_at>=UTC_TIMESTAMP()-INTERVAL 180 DAY
            ORDER BY updated_at DESC,id DESC LIMIT 100
            """,(r,n) -> new Conversation(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant().toString()),
            actor.tenantId(),actor.companyId(),actor.userId());
    }

    Conversation create(AiActor actor,String assistantCode) {
        String id=insert("""
            INSERT INTO ai_conversation(tenant_id,company_id,user_id,assistant_code,title)
            VALUES(?,?,?,?,'新会话')
            """,actor.tenantId(),actor.companyId(),actor.userId(),assistantCode);
        return own(actor,id);
    }

    Conversation own(AiActor actor,String id) {
        AiInputs.id(id);
        return jdbc.query("""
            SELECT id,assistant_code,title,created_at FROM ai_conversation
            WHERE tenant_id=? AND company_id=? AND user_id=? AND id=? AND updated_at>=UTC_TIMESTAMP()-INTERVAL 180 DAY
            """,(r,n) -> new Conversation(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant().toString()),
            actor.tenantId(),actor.companyId(),actor.userId(),id).stream().findFirst().orElseThrow(AiException::missing);
    }

    void delete(AiActor actor,String id) {
        own(actor,id);
        jdbc.update("DELETE FROM ai_conversation WHERE tenant_id=? AND company_id=? AND user_id=? AND id=?",
                actor.tenantId(),actor.companyId(),actor.userId(),id);
    }

    String acquire(AiActor actor,String id) {
        String token=UUID.randomUUID().toString();
        int changed=jdbc.update("""
            UPDATE ai_conversation SET busy_token=?,busy_until=UTC_TIMESTAMP(3)+INTERVAL 120 SECOND
            WHERE tenant_id=? AND company_id=? AND user_id=? AND id=?
              AND (busy_token IS NULL OR busy_until<UTC_TIMESTAMP(3))
            """,token,actor.tenantId(),actor.companyId(),actor.userId(),id);
        if (changed!=1) throw new AiException(409,"AI_CONVERSATION_BUSY","会话正在回答，请稍后再试");
        return token;
    }

    void release(AiActor actor,String id,String token) {
        jdbc.update("""
            UPDATE ai_conversation SET busy_token=NULL,busy_until=NULL
            WHERE tenant_id=? AND company_id=? AND user_id=? AND id=? AND busy_token=?
            """,actor.tenantId(),actor.companyId(),actor.userId(),id,token);
    }

    List<Stored> messages(AiActor actor,String id,int limit) {
        return jdbc.query("""
            SELECT m.id,m.role,m.content,m.citations,m.source_receipts,m.tools,m.created_at
            FROM ai_message m JOIN ai_conversation c ON c.tenant_id=m.tenant_id AND c.id=m.conversation_id
            WHERE c.tenant_id=? AND c.company_id=? AND c.user_id=? AND c.id=?
            ORDER BY m.id DESC LIMIT ?
            """,(r,n) -> new Stored(r.getString(1),r.getString(2),r.getString(3),
                  json.readValue(r.getString(4),new TypeReference<List<AssistantRuntime.Citation>>(){}),
                  json.readValue(r.getString(5),new TypeReference<List<Receipt>>(){}),
                  json.readValue(r.getString(6),new TypeReference<List<AssistantRuntime.ToolResult>>(){}),
                  r.getTimestamp(7).toInstant().toString()),actor.tenantId(),actor.companyId(),actor.userId(),id,limit);
    }

    @Transactional
    public String complete(AiActor actor,String id,String token,String question,AssistantRuntime.Answer answer) {
        int changed=jdbc.update("""
            UPDATE ai_conversation SET title=IF(title='新会话',?,title),updated_at=UTC_TIMESTAMP(3),busy_token=NULL,busy_until=NULL
            WHERE tenant_id=? AND company_id=? AND user_id=? AND id=? AND busy_token=?
            """,question.substring(0,Math.min(80,question.length())),actor.tenantId(),actor.companyId(),actor.userId(),id,token);
        if (changed!=1) throw AiException.missing();
        insert("""
            INSERT INTO ai_message(tenant_id,conversation_id,role,content,citations,source_receipts,tools)
            VALUES(?,?,'user',?,JSON_ARRAY(),JSON_ARRAY(),JSON_ARRAY())
            """,actor.tenantId(),id,question);
        return insert("""
            INSERT INTO ai_message(tenant_id,conversation_id,role,content,citations,source_receipts,tools,model_id,tokens)
            VALUES(?,?,'assistant',?,?,?,?,?,?)
            """,actor.tenantId(),id,answer.text(),json.writeValueAsString(answer.citations()),
                json.writeValueAsString(answer.receipts()),json.writeValueAsString(answer.tools()),
                answer.modelId(),answer.tokens());
    }

    void feedback(AiActor actor,String id,String rating,String reason,String correction) {
        AiInputs.id(id);
        if (!Set.of("up","down").contains(Objects.toString(rating,""))) throw AiException.invalid("请选择有效评价");
        int changed=jdbc.update("""
            INSERT INTO ai_feedback(tenant_id,message_id,user_id,rating,reason,correction)
            SELECT m.tenant_id,m.id,c.user_id,?,?,? FROM ai_message m JOIN ai_conversation c
              ON c.tenant_id=m.tenant_id AND c.id=m.conversation_id
            WHERE c.tenant_id=? AND c.company_id=? AND c.user_id=? AND m.id=? AND m.role='assistant'
            ON DUPLICATE KEY UPDATE rating=?,reason=?,correction=?
            """,rating,reason,correction,actor.tenantId(),actor.companyId(),actor.userId(),id,rating,reason,correction);
        if (changed==0 && jdbc.queryForList("""
            SELECT m.id FROM ai_message m JOIN ai_conversation c ON c.tenant_id=m.tenant_id AND c.id=m.conversation_id
            WHERE c.tenant_id=? AND c.company_id=? AND c.user_id=? AND m.id=? AND m.role='assistant'
            """,actor.tenantId(),actor.companyId(),actor.userId(),id).isEmpty()) throw AiException.missing();
    }

    @Scheduled(cron="0 15 3 * * *",zone="Asia/Shanghai")
    void purge() {
        jdbc.update("DELETE FROM ai_conversation WHERE updated_at<UTC_TIMESTAMP()-INTERVAL 180 DAY LIMIT 2000");
        jdbc.update("DELETE FROM ai_uncovered_question WHERE last_seen<UTC_TIMESTAMP()-INTERVAL 180 DAY LIMIT 2000");
    }

    void uncovered(AiActor actor,String question) {
        jdbc.update("""
            INSERT INTO ai_uncovered_question(tenant_id,company_id,user_id,question_hash,requests,last_seen)
            VALUES(?,?,?,SHA2(?,256),1,UTC_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE requests=LEAST(2147483647,requests+1),last_seen=UTC_TIMESTAMP(3)
            """,actor.tenantId(),actor.companyId(),actor.userId(),question);
    }

    private String insert(String query,Object... values) {
        var key=new GeneratedKeyHolder();
        jdbc.update(c -> {
            var statement=c.prepareStatement(query,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement;
        },key);
        return Objects.requireNonNull(key.getKey()).toString();
    }
}
