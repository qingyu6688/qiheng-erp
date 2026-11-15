package cn.erp.jobs;

import cn.erp.shared.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxService {
    public record Event(long id,long tenantId,String aggregateType,long aggregateId,int version,String type,String payload,String owner,int attempts) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxService(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

    /** 要求在业务事务内调用，业务回滚时事件也回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public long publish(long tenant,String aggregate,long aggregateId,int version,String type,Object payload) {
        if(tenant<=0 || aggregateId<=0 || version<0 || aggregate==null || aggregate.isBlank() || aggregate.length()>32 ||
                type==null || type.isBlank() || type.length()>64) {
            throw new ApiException(400,"VALIDATION_FAILED","事件来源或类型无效");
        }
        long id=Ids.next();
        jdbc.update("INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload) VALUES(?,?,?,?,?,?,?)",
                id,tenant,aggregate,aggregateId,version,type,json.writeValueAsString(payload));
        return id;
    }

    @Transactional
    public List<Event> claim(int limit) {
        if(limit<1 || limit>100) {throw new IllegalArgumentException("任务批量数须在1到100之间");}
        var ids=jdbc.queryForList("""
                SELECT id FROM outbox_event WHERE (status='pending' AND next_attempt_at<=UTC_TIMESTAMP(3))
                OR (status='processing' AND lease_until<UTC_TIMESTAMP(3))
                ORDER BY next_attempt_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                """,Long.class,limit);
        return ids.stream().map(id->{
            String owner=UUID.randomUUID().toString();
            jdbc.update("UPDATE outbox_event SET status='processing',lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 60 SECOND),attempts=attempts+1 WHERE id=?",owner,id);
            return jdbc.queryForObject("""
                    SELECT * FROM outbox_event WHERE id=?
                    """,(r,n)->new Event(r.getLong("id"),r.getLong("tenant_id"),r.getString("aggregate_type"),r.getLong("aggregate_id"),r.getInt("aggregate_version"),
                    r.getString("event_type"),r.getString("payload"),r.getString("lease_owner"),r.getInt("attempts")),id);
        }).toList();
    }

    @Transactional
    public boolean failed(Event event) {
        long seconds=Math.min(3600,1L<<Math.min(12,event.attempts()));
        int updated=jdbc.update("""
                UPDATE outbox_event SET status=?,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL ? SECOND),
                lease_owner=NULL,lease_until=NULL,last_error='消费失败，请按事件编号检查服务日志'
                WHERE id=? AND status='processing' AND lease_owner=?
                """,event.attempts()>=5?"dead":"pending",seconds,event.id(),event.owner());
        return updated==1 && event.attempts()>=5;
    }

    @Transactional
    public void replay(ActorScope scope,long id) {
        int updated=jdbc.update("UPDATE outbox_event SET status='pending',attempts=0,next_attempt_at=UTC_TIMESTAMP(3),last_error=NULL WHERE tenant_id=? AND id=? AND status='dead'",
                scope.tenantId(),id);
        if(updated!=1) {throw new ApiException(409,"STATE_CONFLICT","事件不存在或当前状态不允许重放");}
    }

    @Transactional
    public int cleanExpiredCommands() {
        return jdbc.update("DELETE FROM idempotency_record WHERE expire_at<UTC_TIMESTAMP(3) AND status IN ('succeeded','failed') ORDER BY expire_at LIMIT 1000");
    }
}
