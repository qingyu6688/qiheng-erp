package cn.erp.inventory;

import cn.erp.identity.CompanyAccess;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="erp.worker.enabled",havingValue="true",matchIfMissing=true)
public class ReservationExpiryWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ReservationExpiryWorker.class);
    private final JdbcTemplate jdbc;
    private final ReservationService reservations;

    public ReservationExpiryWorker(JdbcTemplate jdbc,ReservationService reservations) {
        this.jdbc=jdbc;this.reservations=reservations;
    }

    /** 每条预留在自己的事务中释放，任务中断后重复扫描仍由数据库状态保证只释放一次。 */
    @Scheduled(fixedDelayString="${erp.worker.reservation-poll-millis:60000}")
    public void poll() {
        try {
            var candidates=jdbc.queryForList("""
                    SELECT id,tenant_id,company_id,warehouse_id FROM inventory_reservation
                    WHERE status='active' AND expire_at<=UTC_TIMESTAMP(3) ORDER BY expire_at,id LIMIT 200
                    """);
            for(var candidate:candidates) {
                long id=((Number)candidate.get("id")).longValue();
                var scope=new CompanyAccess(((Number)candidate.get("tenant_id")).longValue(),0,
                        ((Number)candidate.get("company_id")).longValue(),false,
                        Set.of(((Number)candidate.get("warehouse_id")).longValue()),Set.of());
                try {reservations.expire(scope,id,Instant.now());}
                catch(RuntimeException error) {LOG.warn("预留 {} 到期释放未完成，等待下轮处理，原因 {}",id,error.getClass().getSimpleName());}
            }
        } catch(RuntimeException error) {
            LOG.warn("预留到期扫描暂不可用，等待下轮处理，原因 {}",error.getClass().getSimpleName());
        }
    }
}
