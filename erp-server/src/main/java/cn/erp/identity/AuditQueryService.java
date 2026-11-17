package cn.erp.identity;

import cn.erp.shared.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 审计快照可能包含整张单据，只向拥有公司完整范围的审计人员开放公司记录。 */
@Service
@Transactional(readOnly=true)
class AuditQueryService {
    record Filter(String action,String entityType,Long entityId,Long operatorId,Long companyId,
                  LocalDate dateFrom,LocalDate dateTo,String requestId) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    AuditQueryService(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

    private static final Map<String,List<String>> FINANCIAL_READS=Map.ofEntries(
        Map.entry("gl_config.",List.of("gl_config:view","cost:view")),
        Map.entry("gl_opening.",List.of("gl_opening:view","cost:view")),
        Map.entry("gl_voucher.",List.of("gl_voucher:view","cost:view")),
        Map.entry("gl_business.",List.of("gl_voucher:view","cost:view")),
        Map.entry("gl_closing.",List.of("gl_report:view","cost:view")),
        Map.entry("credit_limit.",List.of("credit_limit:view","cost:view")),
        Map.entry("credit_increase.",List.of("credit_limit:view","cost:view")),
        Map.entry("bad_debt.",List.of("bad_debt:view","cost:view")),
        Map.entry("aging_config.",List.of("credit_limit:view","cost:view")),
        Map.entry("collection.",List.of("collection:view")));

    AdminModels.Page<Map<String,Object>> list(CompanyAccess actor,Filter filter,String cursor,int limit) {
        if(limit<1||limit>200) throw invalid("查询条数为 1 到 200");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),cursor(cursor),actor.userId()));
        var sql=new StringBuilder("SELECT a.*,u.display_name AS operator_name,c.name AS company_name FROM audit_log a LEFT JOIN sys_user u ON u.tenant_id=a.tenant_id AND u.id=a.operator_id LEFT JOIN company c ON c.tenant_id=a.tenant_id AND c.id=a.company_id WHERE a.tenant_id=? AND a.id<? AND (a.company_id IS NULL OR EXISTS(SELECT 1 FROM sys_user_data_scope s WHERE s.tenant_id=a.tenant_id AND s.company_id=a.company_id AND s.user_id=? AND s.scope_type='company'))");
        var companies=jdbc.queryForList("SELECT DISTINCT company_id FROM sys_user_data_scope WHERE tenant_id=? AND user_id=?",Long.class,actor.tenantId(),actor.userId());
        var restricted=new ArrayList<Long>();var resolver=new OrgScopeResolver(jdbc);
        for(long company:companies) if(!resolver.resolve(actor.tenantId(),company,actor.userId()).allDepartments()) restricted.add(company);
        if(!restricted.isEmpty()) {sql.append(" AND (a.company_id IS NULL OR a.company_id NOT IN ("+String.join(",",Collections.nCopies(restricted.size(),"?"))+"))");args.addAll(restricted);}
        financialVisibility(actor,sql,args);
        openVisibility(actor,sql,args,restricted);
        add(sql,args,"a.action",text(filter.action(),64));add(sql,args,"a.object_type",text(filter.entityType(),32));
        add(sql,args,"a.object_id",positive(filter.entityId()));add(sql,args,"a.operator_id",positive(filter.operatorId()));
        add(sql,args,"a.company_id",positive(filter.companyId()));add(sql,args,"a.request_id",text(filter.requestId(),64));
        if(filter.dateFrom()!=null&&filter.dateTo()!=null && (filter.dateTo().isBefore(filter.dateFrom())||filter.dateTo().isAfter(filter.dateFrom().plusYears(1)))) throw invalid("审计日期范围最长为一年");
        if(filter.dateFrom()!=null) {sql.append(" AND a.created_at>=?");args.add(java.sql.Timestamp.from(filter.dateFrom().atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));}
        if(filter.dateTo()!=null) {sql.append(" AND a.created_at<?");args.add(java.sql.Timestamp.from(filter.dateTo().plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));}
        sql.append(" ORDER BY a.id DESC LIMIT ?");args.add(limit+1);
        var rows=jdbc.query(sql.toString(),(r,n)->{
            var item=new LinkedHashMap<String,Object>();
            item.put("id",r.getString("id"));item.put("companyId",r.getString("company_id"));item.put("companyName",r.getString("company_name"));
            item.put("action",r.getString("action"));item.put("entityType",r.getString("object_type"));item.put("entityId",r.getString("object_id"));
            item.put("operatorId",r.getString("operator_id"));item.put("operatorName",r.getString("operator_name"));
            item.put("createdAt",r.getTimestamp("created_at").toInstant().toString());item.put("requestId",r.getString("request_id"));
            item.put("reason",r.getString("reason"));item.put("before",snapshot(actor,r.getString("before_data")));item.put("after",snapshot(actor,r.getString("after_data")));
            return (Map<String,Object>)item;
        },args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new AdminModels.Page<>(items,more?items.getLast().get("id").toString():null);
    }

    /** 财务快照中的金额无法只靠字段名识别，须在分页前过滤整条记录。 */
    private void financialVisibility(CompanyAccess actor, StringBuilder sql, List<Object> args) {
        var masks = new ArrayList<>(List.of(
            "LEFT(a.object_type,3) IN('gl_','ar_')",
            "a.object_type='credit_increase'",
            "LEFT(a.action,3) IN('gl_','ar_')"));
        var visible = new ArrayList<String>();
        var maskArgs = new ArrayList<Object>();
        var visibleArgs = new ArrayList<Object>();
        for (var rule : FINANCIAL_READS.entrySet()) {
            String predicate = "LEFT(a.action," + rule.getKey().length() + ")=?";
            masks.add(predicate);
            maskArgs.add(rule.getKey());
            if (rule.getValue().stream().allMatch(actor::hasPermission)) {
                visible.add(predicate);
                visibleArgs.add(rule.getKey());
            }
        }
        sql.append(" AND (NOT (").append(String.join(" OR ", masks)).append(")");
        args.addAll(maskArgs);
        if (!visible.isEmpty()) {
            sql.append(" OR (c.status='active' AND (")
                .append(String.join(" OR ", visible)).append("))");
            args.addAll(visibleArgs);
        }
        sql.append(")");
    }

    private void openVisibility(CompanyAccess actor,StringBuilder sql,List<Object> args,List<Long> restricted) {
        sql.append(" AND (a.object_type NOT IN ('open_app','open_webhook','open_webhook_delivery')");
        if (actor.hasPermission("open_platform:view")) {
            var companies=jdbc.queryForList("""
                    SELECT s.company_id FROM sys_user_data_scope s JOIN company c ON c.tenant_id=s.tenant_id
                      AND c.id=s.company_id AND c.status='active'
                    WHERE s.tenant_id=? AND s.user_id=? AND s.scope_type='company'
                    """,Long.class,actor.tenantId(),actor.userId()).stream().filter(id -> !restricted.contains(id))
                    .map(String::valueOf).toList();
            String allowed=json.writeValueAsString(companies);
            sql.append("""
                    OR (EXISTS(
                        SELECT 1 FROM open_app app WHERE app.tenant_id=a.tenant_id AND app.id=(CASE a.object_type
                          WHEN 'open_app' THEN a.object_id
                          WHEN 'open_webhook' THEN (SELECT w.app_id FROM open_webhook w WHERE w.tenant_id=a.tenant_id AND w.id=a.object_id)
                          WHEN 'open_webhook_delivery' THEN (SELECT w.app_id FROM open_webhook_delivery d JOIN open_webhook w
                            ON w.tenant_id=d.tenant_id AND w.id=d.webhook_id WHERE d.tenant_id=a.tenant_id AND d.id=a.object_id)
                          ELSE 0 END)
                        AND NOT EXISTS(SELECT 1 FROM open_app_company ac WHERE ac.tenant_id=app.tenant_id AND ac.app_id=app.id
                          AND NOT JSON_CONTAINS(?,JSON_QUOTE(CAST(ac.company_id AS CHAR))))
                      ) AND JSON_CONTAINS(?,COALESCE(JSON_EXTRACT(a.before_data,'$.companyIds'),JSON_ARRAY()))
                        AND JSON_CONTAINS(?,COALESCE(JSON_EXTRACT(a.after_data,'$.companyIds'),JSON_ARRAY())))
                    """);
            args.add(allowed);
            args.add(allowed);
            args.add(allowed);
        }
        sql.append(")");
    }

    private Object snapshot(CompanyAccess actor,String value) {return value==null?null:redact(actor,json.readValue(value,Object.class));}
    private Object redact(CompanyAccess actor,Object value) {
        if(value instanceof Map<?,?> map) {
            var safe=new LinkedHashMap<String,Object>();
            map.forEach((key,item)->{
                String field=key.toString(),name=field.replace("_","").toLowerCase(Locale.ROOT);
                if(name.contains("password")||name.contains("token")||name.contains("secret")||name.contains("storagekey")||name.contains("cookie")) return;
                if((name.contains("cost")||name.contains("purchaseprice")||name.contains("grossmargin"))&&!actor.hasPermission("cost:view")) return;
                if((name.contains("mobile")||name.contains("phone")||name.contains("address")||name.contains("contact"))&&!actor.hasPermission("partner_contact:view")) return;
                if((name.contains("bank")||name.contains("accountnumber")||name.contains("accountno"))&&!actor.hasPermission("cash_account_no:view")) return;
                safe.put(field,redact(actor,item));
            });
            return safe;
        }
        if(value instanceof List<?> list) return list.stream().map(item->redact(actor,item)).toList();
        return value;
    }
    private void add(StringBuilder sql,List<Object> args,String field,Object value) {if(value!=null) {sql.append(" AND ").append(field).append("=?");args.add(value);}}
    private String text(String value,int max) {if(value==null||value.isBlank())return null;if(value.length()>max)throw invalid("审计筛选条件过长");return value;}
    private Long positive(Long value) {if(value!=null&&value<=0)throw invalid("审计对象编号无效");return value;}
    private long cursor(String value) {try {if(value==null)return Long.MAX_VALUE;long id=Long.parseLong(value);if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException error) {throw invalid("分页游标有误");}}
    private ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
