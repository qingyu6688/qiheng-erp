package cn.erp.aitools;

import cn.erp.documents.DocumentAccess;
import cn.erp.fulfillment.ShipmentService;
import cn.erp.identity.CompanyAccess;
import cn.erp.period.PeriodService;
import cn.erp.shared.ApiException;
import cn.erp.stockmisc.StockMiscQueries;
import cn.erp.workbench.DocumentBrowser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
class AiDocumentExplanation {
    private static final int MAX_LINKS_PER_REASON = 8;
    private static final int MAX_REASONS = 20;
    private static final Set<String> PERMISSIONS = Set.of("sales_order:view","shipment:view","period:close");
    private final ShipmentService shipments;
    private final PeriodService periods;
    private final DocumentBrowser browser;
    private final StockMiscQueries miscellaneousStock;

    AiDocumentExplanation(ShipmentService shipments,PeriodService periods,DocumentBrowser browser,StockMiscQueries miscellaneousStock) {
        this.shipments=shipments;
        this.periods=periods;
        this.browser=browser;
        this.miscellaneousStock=miscellaneousStock;
    }

    static void authorize(CompanyAccess actor) {
        if(PERMISSIONS.stream().noneMatch(actor::hasPermission)) throw new ApiException(403,"FORBIDDEN","没有查看单据解释的权限");
    }

    Map<String,Object> query(CompanyAccess actor,AiBusinessInput input) {
        String type=input.required("documentType");
        long id=positiveId(input.required("documentId"));
        return switch(type) {
            case "sales_order" -> {
                DocumentAccess.require(actor,"sales_order:view");
                yield shipment(actor,type,id,shipments.explainOrderShipment(actor,id));
            }
            case "shipment" -> {
                DocumentAccess.require(actor,"shipment:view");
                yield shipment(actor,type,id,shipments.explainShipment(actor,id));
            }
            case "accounting_period" -> {
                DocumentAccess.require(actor,"period:view");
                DocumentAccess.require(actor,"period:close");
                yield period(actor,id);
            }
            default -> throw AiBusinessInput.invalid("不支持解释此类单据");
        };
    }

    private Map<String,Object> shipment(CompanyAccess actor,String type,long id,ShipmentService.Readiness readiness) {
        String path=("sales_order".equals(type)?"/sales-orders/":"/shipments/")+id;
        var all=readiness.blockers().stream().distinct().toList();
        var blockers=all.stream().limit(MAX_REASONS).map(blocker->{
            var result=reason(blocker.code(),blocker.message());
            if(blocker.orderId()!=null&&"shipment".equals(type)) {
                browser.visibleNumber(actor,"sales-orders",blocker.orderId()).ifPresent(number->
                        result.put("links",List.of(link(number,"/sales-orders/"+blocker.orderId()))));
            }
            return result;
        }).toList();
        return result(type,path,readiness.number(),readiness.status(),readiness.allowedActions(),blockers,all.size()>MAX_REASONS);
    }

    private Map<String,Object> period(CompanyAccess actor,long id) {
        var readiness=periods.explainClose(actor,id);
        var blockers=readiness.checks().stream().filter(check->!Boolean.TRUE.equals(check.get("passed")))
                .limit(MAX_REASONS).map(check->{
                    var result=reason(check.get("check").toString(),periodMessage(check));
                    var links=periodLinks(actor,check.get("documents"));
                    if(!links.isEmpty()) result.put("links",links);
                    return result;
                }).toList();
        return result("accounting_period",periodUrl(id),readiness.period(),readiness.status(),readiness.allowedActions(),blockers,false);
    }

    private String periodMessage(Map<String,Object> check) {
        return switch(check.get("check").toString()) {
            case "unposted_documents" -> "本期还有未处理完成的业务单据";
            case "pending_money" -> "本期有待确认的资金单据、未完成退款或待过账期初";
            case "inventory_reconciliation" -> "库存、批次、库位、冻结、预留、成本或账本存在对账差异";
            case "money_reconciliation" -> "资金、往来或凭证存在对账差异";
            case "gl_config" -> "科目、辅助核算、映射或报表配置尚未完成财务确认";
            case "gl_business" -> "本期还有业务凭证待生成";
            case "gl_manual" -> "本期还有凭证待复核记账";
            case "gl_trial" -> "总账借贷试算未通过";
            case "gl_operational" -> "运营分录与业务凭证存在差异";
            case "gl_reports" -> "三大报表勾稽未通过";
            default -> check.get("message").toString();
        };
    }

    private List<Map<String,String>> periodLinks(CompanyAccess actor,Object source) {
        if(!(source instanceof List<?> list)) return List.of();
        var entries=list.stream().filter(Map.class::isInstance).map(value->(Map<?,?>)value)
                .sorted(Comparator.comparing(row->row.get("documentType")+":"+row.get("documentId"))).toList();
        var links=new ArrayList<Map<String,String>>();
        for(var row:entries) {
            String type=row.get("documentType").toString();
            long id=positiveId(row.get("documentId").toString());
            if("accounting_period".equals(type)) links.add(link(row.get("documentNo").toString(),periodUrl(id)));
            else if(Set.of("stock_misc_in","stock_misc_out").contains(type)) {
                String direction=type.substring("stock_misc_".length());
                miscellaneousStock.visibleNumber(actor,id,direction)
                        .ifPresent(number->links.add(link(number,"/stock-misc-"+direction+"/"+id)));
            }
            else {
                DocumentBrowser.SPECS.entrySet().stream().filter(entry->entry.getValue().type().equals(type))
                        .map(Map.Entry::getKey).findFirst().ifPresent(path->
                                browser.visibleNumber(actor,path,id).ifPresent(number->links.add(link(number,"/"+path+"/"+id))));
            }
            if(links.size()==MAX_LINKS_PER_REASON) break;
        }
        return List.copyOf(links);
    }

    private static String periodUrl(long id) {return "/accounting-periods?id="+id;}

    private static Map<String,String> link(String label,String path) {
        return Map.of("label",label,"detailUrl",path);
    }

    private static Map<String,Object> reason(String code,String message) {
        var result=new LinkedHashMap<String,Object>();
        result.put("code",code);
        result.put("message",message);
        return result;
    }

    private static Map<String,Object> result(String type,String path,String number,String status,
                                             List<String> actions,List<Map<String,Object>> blockers,boolean hasMore) {
        var summary=Map.of("documentType",type,"documentNo",number,"documentStatus",status,
                "allowedActions",actions,"checkPassed",blockers.isEmpty());
        var result=new LinkedHashMap<String,Object>();
        result.put("status","ok");
        result.put("asOf",Instant.now().toString());
        result.put("items",blockers);
        result.put("summary",summary);
        result.put("detailUrl",path);
        result.put("hasMore",hasMore);
        return result;
    }

    private static long positiveId(String value) {
        try {
            if(value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value);
        } catch(NumberFormatException outOfRange) {
            throw AiBusinessInput.invalid("单据标识超出范围");
        }
        throw AiBusinessInput.invalid("单据标识无效");
    }
}
