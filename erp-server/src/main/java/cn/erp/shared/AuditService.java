package cn.erp.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditService {
    private static final java.util.Set<String> MILESTONES=java.util.Set.of("sales_order.approve","shipment.post","shipment.sign",
            "goods_receipt.post","receipt.confirm");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private org.springframework.context.ApplicationEventPublisher events = unused -> {};

    @org.springframework.beans.factory.annotation.Autowired
    void events(org.springframework.context.ApplicationEventPublisher events) { this.events=events; }

    public AuditService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(ActorScope scope, String action, String entityType, long entityId, Object before, Object after) {
        jdbc.update("""
                INSERT INTO audit_log(id,tenant_id,company_id,operator_id,action,object_type,object_id,
                  before_data,after_data,request_id) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, Ids.next(), scope.tenantId(), scope.companyId() == 0 ? null : scope.companyId(), scope.userId(),
                action, entityType, entityId, before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after), RequestIds.current());
        if (scope.companyId()>0 && MILESTONES.contains(action) && after instanceof java.util.Map<?,?> row
                && row.get("version") instanceof Number version) {
            events.publishEvent(new BusinessMilestone(scope.tenantId(),scope.companyId(),action,entityId,
                    version.intValue(),java.util.Objects.toString(row.get("status"),"")));
        }
    }
}
