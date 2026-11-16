package cn.erp.sysadmin;

import cn.erp.shared.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@EnableScheduling
public class ApiLogWriter {
    private static final Logger LOG=LoggerFactory.getLogger(ApiLogWriter.class);
    private final ArrayBlockingQueue<ApiObservation> queue=new ArrayBlockingQueue<>(16_384);
    private final AtomicLong dropped=new AtomicLong();
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    private final io.micrometer.core.instrument.Counter failures;
    public ApiLogWriter(JdbcTemplate jdbc,ObjectMapper json,io.micrometer.core.instrument.MeterRegistry meters) {
        this.jdbc=jdbc;this.json=json;failures=meters.counter("erp.api.logs.write.failures");
        meters.gauge("erp.api.logs.queue.size",queue,java.util.Collection::size);meters.gauge("erp.api.logs.dropped",dropped);
    }
    @EventListener public void observe(ApiObservation observation) {
        if(!queue.offer(observation)) {long count=dropped.incrementAndGet();if(count==1||count%1000==0) LOG.warn("接口日志队列已满，累计未入库 {} 条；文件请求日志仍保留",count);}
    }
    public long dropped() {return dropped.get();}
    @Scheduled(fixedDelay=1000)
    public synchronized void flush() {
        var batch=new ArrayList<ApiObservation>();queue.drainTo(batch,2000);if(batch.isEmpty()) return;
        try {
            jdbc.batchUpdate("INSERT IGNORE INTO sys_api_access_log(id,tenant_id,user_id,company_id,request_method,route_pattern,status_code,duration_ms,slow_request,request_id,trace_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",batch,500,(statement,row)->{
                Object[] values={Ids.next(),row.tenantId(),row.userId(),row.companyId(),row.method(),row.route(),row.status(),row.duration(),row.duration()>=1000,row.requestId(),row.traceId()};for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            });
            var errors=batch.stream().filter(row->row.status()>=500).toList();
            if(!errors.isEmpty()) jdbc.batchUpdate("INSERT IGNORE INTO sys_api_error_log(id,tenant_id,user_id,company_id,error_class,error_summary,stack_frames,request_id,trace_id) VALUES(?,?,?,?,?,?,?,?,?)",errors,500,(statement,row)->{
                Object[] values={Ids.next(),row.tenantId(),row.userId(),row.companyId(),Objects.toString(row.errorClass(),"请求未完成"),row.status()==503?"依赖服务暂不可用":"服务端处理失败",json.writeValueAsString(row.frames()),row.requestId(),row.traceId()};for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            });
        } catch(RuntimeException failure) {
            failures.increment();
            for(var row:batch) if(!queue.offer(row)) dropped.incrementAndGet();
            LOG.warn("接口日志批量写入失败，等待下轮重试，异常类型 {}",failure.getClass().getSimpleName());
        }
    }
    @PreDestroy void close() {for(int i=0;i<10&&!queue.isEmpty();i++) flush();}
}
