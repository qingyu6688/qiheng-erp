package cn.erp.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class BusinessCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CommandResultAccess resultAccess;
    private final org.springframework.context.ApplicationEventPublisher events;

    public BusinessCommands(JdbcTemplate jdbc, ObjectMapper json, CommandResultAccess resultAccess,org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.resultAccess = resultAccess;
        this.events=events;
    }

    @Transactional
    public <T> T execute(ActorScope scope, String operation, String key, Object payload,
                        int httpStatus, Supplier<T> command, Class<T> resultType) {
        if (key == null || key.isBlank()) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "缺少幂等键");
        }
        if (!key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ApiException(400, "VALIDATION_FAILED", "幂等键格式有误");
        }
        String serialized=json.writeValueAsString(payload);
        String legacyHash=digest(serialized);
        String hash = digest(json.writeValueAsString(canonical(json.readValue(serialized,Object.class))));
        long claim = Ids.next();
        jdbc.update("""
                INSERT INTO idempotency_record(id,tenant_id,company_id,operation,idempotency_key,
                  request_hash,operator_id,expire_at) VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 7 DAY))
                ON DUPLICATE KEY UPDATE id=id
                """, claim, scope.tenantId(), scope.companyId(), operation, key, hash, scope.userId());
        var entry = jdbc.queryForMap("""
                SELECT id,operator_id,request_hash,response_body FROM idempotency_record
                WHERE tenant_id=? AND company_id=? AND operation=? AND idempotency_key=? FOR UPDATE
                """, scope.tenantId(), scope.companyId(), operation, key);
        if (((Number) entry.get("operator_id")).longValue() != scope.userId()) {
            throw new ApiException(403, "FORBIDDEN", "只有原操作人可以查看请求结果");
        }
        if (((Number) entry.get("id")).longValue() != claim) {
            if (!hash.equals(entry.get("request_hash")) && !legacyHash.equals(entry.get("request_hash"))) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "同一请求标识对应了不同内容");
            }
            if (entry.get("response_body") == null) {
                throw new ApiException(409, "REQUEST_IN_PROGRESS", "请求正在处理，请稍后重试");
            }
            T result=json.readValue(entry.get("response_body").toString(), resultType);
            resultAccess.verify(scope,operation,result);
            return result;
        }
        T result = command.get();
        jdbc.update("UPDATE idempotency_record SET status='succeeded',http_status=?,response_body=? WHERE id=?",
                httpStatus, json.writeValueAsString(result), claim);
        if(scope.companyId()>0) events.publishEvent(new BusinessChanged(scope.tenantId(),scope.companyId(),scope.userId(),operation,claim));
        return result;
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少摘要算法", error);
        }
    }

    private static Object canonical(Object value) {
        if(value instanceof java.util.Map<?,?> map) {
            var sorted=new java.util.TreeMap<String,Object>();
            map.forEach((key,item)->{
                // 新增的可选字段未填写时保持旧请求摘要，允许升级后按原键查询和重试。
                if(item!=null||!java.util.Set.of("salesUserId","buyerUserId","areaId","deliveryAreaId","deliveryAddress","sourceCode","businessUserId","groupBy","agingDate","workflowAssignees","financeQuery","creditApproval","stockSelection","serials").contains(key.toString())) sorted.put(key.toString(),canonical(item));
            });
            return sorted;
        }
        if(value instanceof java.util.List<?> list) {return list.stream().map(BusinessCommands::canonical).toList();}
        return value;
    }
}
