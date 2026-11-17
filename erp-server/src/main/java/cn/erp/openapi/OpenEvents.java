package cn.erp.openapi;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.jobs.EventHandler;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.BusinessMilestone;
import cn.erp.shared.Ids;
import cn.erp.shared.InventoryBalanceChanged;

@Component
class OpenEvents implements EventHandler {
    private static final String SOURCE_EVENT = "open.ready";
    private static final String STOCK = "inventory.balance.changed";
    private static final TypeReference<Map<String,Object>> DATA = new TypeReference<>() {};
    private final OutboxService outbox;
    private final OpenWebhooks hooks;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    OpenEvents(OutboxService outbox,OpenWebhooks hooks,JdbcTemplate jdbc,ObjectMapper json) {
        this.outbox=outbox;
        this.hooks=hooks;
        this.jdbc=jdbc;
        this.json=json;
    }

    @Override public String eventType() { return SOURCE_EVENT; }
    @Override public String consumer() { return "open.webhook"; }

    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void milestone(BusinessMilestone signal) {
        String type=switch(signal.action()) {
            case "sales_order.approve" -> "sales.order.approved";
            case "shipment.post" -> "sales.shipment.posted";
            case "shipment.sign" -> "sales.shipment.signed";
            case "goods_receipt.post" -> "purchase.receipt.posted";
            case "receipt.confirm" -> "finance.receipt.confirmed";
            default -> null;
        };
        if (type==null) return;
        String status=signal.status().isEmpty() && signal.action().equals("receipt.confirm") ? "confirmed" : signal.status();
        Map<String,Object> data=Map.of("documentId",Long.toString(signal.documentId()),"version",signal.version(),"status",status);
        if (subscribed(signal.tenantId(),signal.companyId(),type)) {
            publish(signal.tenantId(),signal.companyId(),type,data);
        }
        if (signal.action().equals("shipment.post") && Set.of("signed","signed_with_diff").contains(signal.status())
                && subscribed(signal.tenantId(),signal.companyId(),"sales.shipment.signed")) {
            publish(signal.tenantId(),signal.companyId(),"sales.shipment.signed",data);
        }
    }

    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void inventory(InventoryBalanceChanged signal) {
        if (!subscribed(signal.tenantId(),signal.companyId(),STOCK)) return;
        long minute=Instant.now().getEpochSecond()/60;
        for (long sku:signal.skuIds()) {
            int inserted=jdbc.update("""
                    INSERT IGNORE INTO open_inventory_event_slot(tenant_id,company_id,sku_id,minute_key) VALUES(?,?,?,?)
                    """,signal.tenantId(),signal.companyId(),sku,minute);
            if (inserted==0) continue;
            long id=publish(signal.tenantId(),signal.companyId(),STOCK,Map.of("skuId",Long.toString(sku)));
            jdbc.update("UPDATE outbox_event SET next_attempt_at=? WHERE tenant_id=? AND id=?",
                    Timestamp.from(Instant.ofEpochSecond((minute+1)*60)),signal.tenantId(),id);
        }
    }

    private boolean subscribed(long tenant,long company,String type) {
        return !jdbc.queryForList("""
                SELECT w.id FROM open_webhook w
                JOIN open_app a ON a.tenant_id=w.tenant_id AND a.id=w.app_id
                JOIN open_app_company c ON c.tenant_id=a.tenant_id AND c.app_id=a.id
                WHERE w.tenant_id=? AND c.company_id=? AND w.enabled=1 AND a.enabled=1
                  AND (a.expires_at IS NULL OR a.expires_at>UTC_TIMESTAMP(3))
                  AND JSON_CONTAINS(w.events,JSON_QUOTE(?)) AND JSON_CONTAINS(a.scopes,JSON_QUOTE(?))
                LIMIT 1
                """,Long.class,tenant,company,type,OpenPolicy.EVENT_SCOPES.get(type)).isEmpty();
    }

    private long publish(long tenant,long company,String type,Map<String,Object> data) {
        return outbox.publish(tenant,"open_event",Ids.next(),0,SOURCE_EVENT,
                Map.of("companyId",Long.toString(company),"type",type,"data",data));
    }

    @Override
    public void handle(OutboxService.Event event) {
        var payload=json.readTree(event.payload());
        long company=OpenPolicy.id(payload.path("companyId").asString());
        String type=payload.path("type").asString();
        if (!OpenPolicy.EVENT_SCOPES.containsKey(type)) throw new IllegalStateException("开放平台事件类型不存在");
        Map<String,Object> data=json.readValue(payload.path("data").toString(),DATA);
        if (STOCK.equals(type)) data=stock(event.tenantId(),company,data.get("skuId").toString());
        hooks.fanOut(event.tenantId(),event.id(),company,type,data);
    }

    private Map<String,Object> stock(long tenant,long company,String sku) {
        var quantities=jdbc.queryForMap("""
                SELECT COALESCE(SUM(on_hand_qty),0) on_hand,COALESCE(SUM(on_hand_qty-reserved_qty-blocked_qty),0) available
                FROM inventory_balance WHERE tenant_id=? AND company_id=? AND sku_id=? AND location_key='' AND lot_key=''
                """,tenant,company,OpenPolicy.id(sku));
        return Map.of("skuId",sku,"onHandQty",quantities.get("on_hand").toString(),"availableQty",quantities.get("available").toString());
    }
}
