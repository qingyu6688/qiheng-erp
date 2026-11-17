package cn.erp.portal;

import cn.erp.fulfillment.ShipmentQueries;
import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.salesorder.SalesOrderQueries;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
class PortalTicketAssist {
    private static final int RECENT_COUNT=5;
    private static final Set<String> ORDER_FIELDS=Set.of("id","documentNo","status","fulfillmentStatus","businessDate","totalAmount");
    private static final Set<String> SHIPMENT_FIELDS=Set.of("id","documentNo","status","businessDate","signDate");
    private final PortalTickets tickets;
    private final SalesOrderQueries orders;
    private final ShipmentQueries shipments;
    private final ReportService reports;

    PortalTicketAssist(PortalTickets tickets,SalesOrderQueries orders,ShipmentQueries shipments,ReportService reports) {
        this.tickets=tickets;
        this.orders=orders;
        this.shipments=shipments;
        this.reports=reports;
    }

    record Assistance(String customerName,List<Map<String,Object>> recentOrders,List<Map<String,Object>> recentShipments,
                      String openReceivable,String replySuggestion,String suggestionSource) {}

    Assistance read(CompanyAccess actor,long ticketId) {
        var context=tickets.staffContext(actor,ticketId);
        long buyer=context.partnerId();
        var recentOrders=actor.hasPermission("sales_order:view")
                ? rows(orders.list(actor,"",null,Long.toString(buyer),null,RECENT_COUNT),ORDER_FIELDS):List.<Map<String,Object>>of();
        var recentShipments=actor.hasPermission("shipment:view")
                ? rows(shipments.list(actor,null,false,buyer,null,RECENT_COUNT),SHIPMENT_FIELDS):List.<Map<String,Object>>of();
        return new Assistance(context.customerName(),recentOrders,recentShipments,receivable(actor,buyer),
                suggestion(context.ticket().category()),"template");
    }

    private String receivable(CompanyAccess actor,long buyer) {
        if(!actor.hasPermission("report:receivable_aging") || !actor.allWarehouses()) return null;
        var snapshot=reports.snapshot(actor,ReportType.RECEIVABLE_AGING,new ReportQuery(null,null,null,null,buyer,null,false));
        return snapshot.summary().getOrDefault("openAmount","0.00").toString();
    }

    private List<Map<String,Object>> rows(Map<String,Object> page,Set<String> allowed) {
        if(!(page.get("items") instanceof List<?> items)) return List.of();
        return items.stream().filter(Map.class::isInstance).map(item->select((Map<?,?>)item,allowed)).toList();
    }

    private Map<String,Object> select(Map<?,?> row,Set<String> allowed) {
        var fields=new LinkedHashMap<String,Object>();
        for(var entry:row.entrySet()) if(entry.getKey() instanceof String key && allowed.contains(key)) fields.put(key,entry.getValue());
        return fields;
    }

    private String suggestion(String category) {
        return switch(category) {
            case "order" -> "您好，已收到您的订单咨询。我们会核对订单和履约进度，并在工单中回复。";
            case "logistics" -> "您好，已收到您的物流咨询。我们会核对发货与签收信息，请留意此工单的更新。";
            case "invoice" -> "您好，已收到您的发票咨询。我们会核对开票状态，并通过工单告知处理结果。";
            case "complaint","quality","refund" -> "您好，已收到您反馈的问题。我们会核实相关记录并告知下一步处理方式。";
            default -> "您好，已收到您的问题。我们核对相关记录后会在工单中回复。";
        };
    }
}
