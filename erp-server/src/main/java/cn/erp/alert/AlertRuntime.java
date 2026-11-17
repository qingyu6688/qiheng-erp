package cn.erp.alert;

import cn.erp.jobs.EventHandler;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.BusinessChanged;
import cn.erp.shared.Ids;
import cn.erp.shared.InventoryBalanceChanged;
import cn.erp.shared.ManagedJobs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
class AlertRuntime implements EventHandler {
    private static final Map<String,List<AlertKind>> BUSINESS=Map.ofEntries(
            Map.entry("sales_order",List.of(AlertKind.CREDIT_EXCEEDED,AlertKind.WORKFLOW_OVERDUE)),
            Map.entry("credit_limit",List.of(AlertKind.CREDIT_EXCEEDED)),
            Map.entry("shipment",List.of(AlertKind.CREDIT_EXCEEDED,AlertKind.NEGATIVE_MARGIN,AlertKind.RECEIVABLE_OVERDUE)),
            Map.entry("sales_return",List.of(AlertKind.CREDIT_EXCEEDED,AlertKind.RECEIVABLE_OVERDUE)),
            Map.entry("receipt",List.of(AlertKind.CREDIT_EXCEEDED,AlertKind.RECEIVABLE_OVERDUE,AlertKind.COLLECTION_DUE)),
            Map.entry("settlement",List.of(AlertKind.CREDIT_EXCEEDED,AlertKind.RECEIVABLE_OVERDUE)),
            Map.entry("purchase_order",List.of(AlertKind.PRICE_DEVIATION,AlertKind.WORKFLOW_OVERDUE)),
            Map.entry("qc_inspection",List.of(AlertKind.QC_FAILED)),
            Map.entry("payment",List.of(AlertKind.DUPLICATE_PAYMENT)),
            Map.entry("document_reversal",List.of(AlertKind.FREQUENT_REVERSAL,AlertKind.NEGATIVE_MARGIN,AlertKind.RECEIVABLE_OVERDUE)),
            Map.entry("reversal",List.of(AlertKind.FREQUENT_REVERSAL,AlertKind.NEGATIVE_MARGIN,AlertKind.RECEIVABLE_OVERDUE)));
    private static final List<AlertKind> INVENTORY=List.of(AlertKind.STOCK_BELOW_SAFETY,AlertKind.STOCK_SLOW_MOVING,
            AlertKind.BATCH_NEAR_EXPIRY,AlertKind.BATCH_EXPIRED);
    private final JdbcTemplate jdbc;
    private final AlertScan scan;
    private final AlertRules rules;
    private final OutboxService outbox;
    private final ObjectMapper json;

    AlertRuntime(JdbcTemplate jdbc,AlertScan scan,AlertRules rules,OutboxService outbox,ObjectMapper json,ManagedJobs jobs) {
        this.jdbc=jdbc;this.scan=scan;this.rules=rules;this.outbox=outbox;this.json=json;
        jobs.register("alert.scan",this::scheduled);
    }

    record Signal(long companyId,List<String> codes) {}
    private record Company(long tenant,long id) {}

    @EventListener void business(BusinessChanged event) {
        var area=event.operation().split("\\.",2)[0];
        signal(event.tenantId(),event.companyId(),BUSINESS.getOrDefault(area,List.of()));
    }

    @EventListener void inventory(InventoryBalanceChanged event) {
        signal(event.tenantId(),event.companyId(),INVENTORY);
    }

    private void signal(long tenant,long company,List<AlertKind> kinds) {
        if (tenant<=0 || company<=0 || kinds.isEmpty() || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        var accumulator=TransactionSynchronizationManager.getSynchronizations().stream().filter(Signals.class::isInstance)
                .map(Signals.class::cast).findFirst().orElseGet(()->{
                    var created=new Signals();
                    TransactionSynchronizationManager.registerSynchronization(created);
                    return created;
                });
        accumulator.affected.computeIfAbsent(new Company(tenant,company),key->new LinkedHashSet<>()).addAll(kinds);
    }

    private final class Signals implements TransactionSynchronization {
        private final Map<Company,Set<AlertKind>> affected=new LinkedHashMap<>();
        @Override public void beforeCommit(boolean readOnly) {
            if (readOnly) return;
            affected.forEach((scope,kinds)->outbox.publish(scope.tenant(),"alert_signal",Ids.next(),0,eventType(),
                    new Signal(scope.id(),kinds.stream().map(kind->kind.code).toList())));
        }
    }

    @Override public String eventType() {return "alert.refresh";}
    @Override public String consumer() {return "alert.center";}

    @Override public void handle(OutboxService.Event event) {
        var signal=json.readValue(event.payload(),Signal.class);
        if (!companyActive(event.tenantId(),signal.companyId())) return;
        var kinds=signal.codes().stream().map(AlertKind::parse).distinct().sorted().toList();
        var now=Instant.now();
        for (var kind:kinds) scan.scan(event.tenantId(),signal.companyId(),kind,now,true);
    }

    private boolean companyActive(long tenant,long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM company WHERE tenant_id=? AND id=? AND status='active')",
                Boolean.class,tenant,id));
    }

    private void scheduled(long tenant) {
        var failures=new ArrayList<RuntimeException>();
        var now=Instant.now();
        for (long company:jdbc.queryForList("SELECT id FROM company WHERE tenant_id=? AND status='active' ORDER BY id",Long.class,tenant)) {
            for (var rule:rules.list(tenant,company)) {
                if (!rule.enabled() || rule.nextRun()!=null && rule.nextRun().isAfter(now)) continue;
                try {scan.scan(tenant,company,rule.kind(),now,false);}
                catch (RuntimeException error) {failures.add(new IllegalStateException("预警规则 "+rule.kind().code+" 扫描失败",error));}
            }
        }
        if (!failures.isEmpty()) {
            var outcome=new IllegalStateException("部分预警规则扫描失败，下次调度将重试");
            failures.forEach(outcome::addSuppressed);
            throw outcome;
        }
    }
}
