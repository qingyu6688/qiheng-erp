package cn.erp.inventory;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static cn.erp.inventory.InventoryModels.*;

@SpringBootTest(classes=ErpApplication.class,properties={
        "spring.data.redis.port=1","spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms","erp.worker.enabled=false"})
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class InventoryRedisOutageIT {
    @Autowired StringRedisTemplate redis;
    @Autowired InventoryService inventory;
    @Autowired ReservationService reservations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void T13UnavailableRedisDoesNotChangeInventoryConsistency() {
        assertThatThrownBy(()->redis.getConnectionFactory().getConnection().ping())
                .isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
        var scope=new CompanyAccess(70101,1,70201,true,Set.of(),Set.of());
        var key=new Key(70301,71002);
        long inbound=Ids.next(),outbound=Ids.next();
        inventory.receive(scope,new Posting(key,new Source("outage_test",inbound,inbound),
                LocalDate.of(2026,11,15),70601,"opening_in"),new BigDecimal("1"),new BigDecimal("1.00"));
        long reservation=reservations.reserve(scope,List.of(new ReservationRequest(71302,key,new BigDecimal("1"),
                Instant.now().plusSeconds(60)))).getFirst();
        inventory.issue(scope,new Posting(key,new Source("outage_test",outbound,outbound),
                LocalDate.of(2026,11,15),70601,"sales_out"),new BigDecimal("1"),reservation);
        assertThat(inventory.balance(scope,key).onHand()).isEqualByComparingTo("0");
        assertThat(inventory.balance(scope,key).reserved()).isEqualByComparingTo("0");
        assertThat(inventory.balance(scope,key).cost()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT SUM(on_hand_delta) FROM inventory_movement WHERE tenant_id=70101 AND sku_id=71002",BigDecimal.class))
                .isEqualByComparingTo("0");
    }
}
