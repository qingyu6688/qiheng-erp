package cn.erp.shared;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommandResults {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CommandResultAccess resultAccess;

    public CommandResults(JdbcTemplate jdbc,ObjectMapper json,CommandResultAccess resultAccess) {this.jdbc=jdbc;this.json=json;this.resultAccess=resultAccess;}

    public Map<String,Object> get(ActorScope scope,String operation,String key) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{1,64}")) {throw new ApiException(400,"VALIDATION_FAILED","幂等键格式有误");}
        var rows=jdbc.queryForList("SELECT status,operator_id,response_body FROM idempotency_record WHERE tenant_id=? AND company_id=? AND operation=? AND idempotency_key=?",
                scope.tenantId(),scope.companyId(),operation,key);
        if(rows.isEmpty()) {throw new ApiException(404,"NOT_FOUND","尚未找到该请求的处理结果");}
        var row=rows.getFirst();
        if(((Number)row.get("operator_id")).longValue()!=scope.userId()) {throw new ApiException(403,"FORBIDDEN","只有原操作人可以查看请求结果");}
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("status",row.get("status"));
        Object response=row.get("response_body")==null?null:json.readValue(row.get("response_body").toString(),Object.class);
        resultAccess.verify(scope,operation,response);
        if(operation.startsWith("company.") && response instanceof Map<?,?> company) {
            Long visible=jdbc.queryForObject("SELECT COUNT(*) FROM org_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",
                    Long.class,scope.tenantId(),scope.userId(),company.get("id"));
            if(visible==null || visible==0) {throw new ApiException(404,"NOT_FOUND","资料不存在或无权查看");}
        }
        result.put("response",response);
        return result;
    }
}
