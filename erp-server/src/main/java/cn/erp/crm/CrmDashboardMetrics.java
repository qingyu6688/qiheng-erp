package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CrmDashboardMetrics {
    private final CrmStatisticsService statistics;

    CrmDashboardMetrics(CrmStatisticsService statistics) {this.statistics=statistics;}

    public record Values(BigDecimal opportunityAmount,BigDecimal won,BigDecimal lost,BigDecimal contractAmount,
                         BigDecimal contractTarget,String dataSource) {}

    public Values metrics(CompanyAccess actor,LocalDate from,LocalDate to) {
        if (!actor.hasPermission("crm_statistics:view") || !actor.allWarehouses())
            throw new ApiException(403,"FORBIDDEN","CRM 经营汇总需要整仓数据范围和统计权限");
        var funnel=statistics.statistics(actor,"funnel",from,to,null,null);
        var performance=statistics.statistics(actor,"performance",from,to,null,null);
        var items=rows(funnel,"items");
        var targets=rows(performance,"targets").stream().filter(row->"contract".equals(row.get("kind"))).toList();
        // 部门目标与个人目标可能重复，优先按部门汇总。
        var department=targets.stream().filter(row->"department".equals(row.get("targetType"))).toList();
        return new Values(sum(items,"expectedAmount"),sum(items,"wonCount"),sum(items,"lostCount"),
                sum(rows(performance,"items"),"contractAmount"),sum(department.isEmpty()?targets:department,"amount"),
                funnel.get("dataSource").toString());
    }

    private static BigDecimal sum(List<Map<String,Object>> values,String key) {
        return values.stream().map(row->row.get(key)).filter(java.util.Objects::nonNull)
                .map(value->new BigDecimal(value.toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> result,String key) {
        return (List<Map<String,Object>>)result.getOrDefault(key,List.of());
    }
}
