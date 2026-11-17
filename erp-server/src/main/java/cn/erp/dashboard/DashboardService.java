package cn.erp.dashboard;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
class DashboardService {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<String>> STORED_LAYOUT=new TypeReference<>() {};
    private final DashboardMetrics metrics;
    private final DashboardCache cache;
    private final DashboardOperationalSources sources;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    DashboardService(DashboardMetrics metrics,DashboardCache cache,DashboardOperationalSources sources,JdbcTemplate jdbc,ObjectMapper json) {
        this.metrics=metrics;this.cache=cache;this.sources=sources;this.jdbc=jdbc;this.json=json;
    }

    record LayoutInput(Long version,List<String> metrics) {}
    record Layout(long version,String preset,List<String> metrics) {}
    record Result(String period,String dateFrom,String dateTo,List<DashboardMetrics.MetricData> items) {}

    List<DashboardMetric.Definition> available(CompanyAccess actor) {
        authorize(actor);
        return Arrays.stream(DashboardMetric.values()).filter(metric->metric.available(actor)).map(DashboardMetric::definition).toList();
    }

    Result data(CompanyAccess actor,String month,String selection,boolean refresh) {
        authorize(actor);
        var today=LocalDate.now(BUSINESS_ZONE);
        var requested=period(month,today);
        if (selection!=null && selection.length()>150) throw invalid("驾驶舱指标参数过长");
        var permitted=selection==null?layout(actor).metrics():validate(actor,Arrays.asList(selection.split(",",-1)));
        if (refresh && !permitted.isEmpty()) cache.refresh(actor);
        var items=permitted.stream().map(DashboardMetric::find).map(metric->cache.result(actor,metric,requested,refresh,
                ()->metrics.load(actor,metric,requested))).toList();
        return new Result(requested.month().toString(),requested.from().toString(),requested.end().toString(),items);
    }

    @Transactional(readOnly=true)
    Layout layout(CompanyAccess actor) {
        authorize(actor);
        String preset=sources.rolePreset(actor);
        var rows=jdbc.queryForList("SELECT layout_json,version FROM dash_layout WHERE tenant_id=? AND company_id=? AND user_id=?",
                actor.tenantId(),actor.companyId(),actor.userId());
        if (rows.isEmpty()) return new Layout(0L,preset,filter(actor,preset(preset)));
        return stored(actor,preset,rows.getFirst());
    }

    @Transactional
    Layout save(CompanyAccess actor,LayoutInput input) {
        authorize(actor);
        if (input==null || input.version()==null || input.version()<0) throw invalid("请提供布局版本");
        var ordered=validate(actor,input.metrics());
        jdbc.update("""
                INSERT INTO dash_layout(id,tenant_id,company_id,user_id,layout_json) VALUES(?,?,?,?,JSON_ARRAY())
                ON DUPLICATE KEY UPDATE version=version
                """,Ids.next(),actor.tenantId(),actor.companyId(),actor.userId());
        int updated=jdbc.update("""
                UPDATE dash_layout SET layout_json=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND user_id=? AND version=?
                """,json.writeValueAsString(ordered),actor.tenantId(),actor.companyId(),actor.userId(),input.version());
        if (updated!=1) throw new ApiException(409,"VERSION_CONFLICT","驾驶舱布局已更新，请刷新页面后重试");
        return new Layout(input.version()+1,sources.rolePreset(actor),ordered);
    }

    private Layout stored(CompanyAccess actor,String preset,Map<String,Object> row) {
        var values=json.readValue(row.get("layout_json").toString(),STORED_LAYOUT);
        return new Layout(((Number)row.get("version")).longValue(),preset,filter(actor,values));
    }

    private static List<String> filter(CompanyAccess actor,List<String> values) {
        return values.stream().map(DashboardMetric::find).filter(metric->metric!=null && metric.available(actor))
                .map(DashboardMetric::code).distinct().toList();
    }

    private static List<String> validate(CompanyAccess actor,List<String> selected) {
        if (selected==null || selected.size()>DashboardMetric.values().length) throw invalid("请选择有效的驾驶舱指标");
        var allowed=new LinkedHashSet<String>();
        for (String code : selected) {
            var metric=DashboardMetric.find(code);
            if (metric==null) throw invalid("驾驶舱指标编码无效");
            if (!metric.available(actor)) throw new ApiException(403,"FORBIDDEN","没有查看所选指标的权限");
            if (!allowed.add(code)) throw invalid("驾驶舱指标不能重复");
        }
        return List.copyOf(allowed);
    }

    private static List<String> preset(String role) {
        return switch (role) {
            case "sales" -> List.of("sales","receivables","crm","gross_margin");
            case "finance" -> List.of("receivables","finance","gross_margin","sales","purchase");
            case "warehouse" -> List.of("inventory","purchase","production");
            default -> Arrays.stream(DashboardMetric.values()).map(DashboardMetric::code).toList();
        };
    }

    private static DashboardMetrics.Period period(String value,LocalDate today) {
        if (value==null) return new DashboardMetrics.Period(YearMonth.from(today),today);
        if (!value.matches("[0-9]{4}-(?:0[1-9]|1[0-2])")) throw invalid("请选择有效的统计月份");
        var month=YearMonth.parse(value);
        if (month.getYear()<=1900 || month.isAfter(YearMonth.from(today))) throw invalid("统计月份超出可查询范围");
        return new DashboardMetrics.Period(month,today);
    }

    private static void authorize(CompanyAccess actor) {
        if (!actor.hasPermission("dashboard:view")) throw new ApiException(403,"FORBIDDEN","没有查看经营驾驶舱的权限");
    }

    private static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
