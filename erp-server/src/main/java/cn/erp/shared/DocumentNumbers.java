package cn.erp.shared;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentNumbers {
    private final JdbcTemplate jdbc;
    private final ConfiguredDocumentNumber rules;

    public DocumentNumbers(JdbcTemplate jdbc, ConfiguredDocumentNumber rules) {
        this.jdbc=jdbc;
        this.rules=rules;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public String next(ActorScope scope, String type, LocalDate date) {
        if (date==null || type==null) throw new ApiException(400,"VALIDATION_FAILED","单据类型或日期无效");
        String custom=rules.document(scope,type,date);
        return custom==null ? defaultNumber(scope,"PR_RETURN".equals(type) ? "PR" : type,YearMonth.from(date)) : custom;
    }

    /** 在独立短事务内分配单号，业务回滚时允许断号。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public String next(ActorScope scope,String type,YearMonth month) {
        return defaultNumber(scope,type,month);
    }

    private String defaultNumber(ActorScope scope,String type,YearMonth month) {
        if (type==null || !type.matches("[A-Z][A-Z0-9_]{0,31}") || month==null || month.getYear()<1000 || month.getYear()>9999) {
            throw new ApiException(400,"VALIDATION_FAILED","单据类型或月份无效");
        }
        var companies=jdbc.queryForList("SELECT code FROM company WHERE tenant_id=? AND id=? AND status='active'",String.class,scope.tenantId(),scope.companyId());
        if (companies.isEmpty()) {throw new ApiException(404,"NOT_FOUND","公司不存在或已停用");}
        String period=month.format(DateTimeFormatter.ofPattern("uuuuMM"));
        jdbc.update("""
                INSERT INTO document_sequence(id,tenant_id,company_id,document_type,period,next_value)
                VALUES(?,?,?,?,?,1) ON DUPLICATE KEY UPDATE id=id
                """,Ids.next(),scope.tenantId(),scope.companyId(),type,period);
        var row=jdbc.queryForMap("SELECT id,next_value FROM document_sequence WHERE tenant_id=? AND company_id=? AND document_type=? AND period=? FOR UPDATE",
                scope.tenantId(),scope.companyId(),type,period);
        long value=((Number)row.get("next_value")).longValue();
        if(value>999999) {throw new ApiException(422,"SEQUENCE_EXHAUSTED","本月单号已用完");}
        jdbc.update("UPDATE document_sequence SET next_value=next_value+1 WHERE id=?",row.get("id"));
        return type+"-"+companies.getFirst()+"-"+period+"-"+String.format(java.util.Locale.ROOT,"%06d",value);
    }
}
