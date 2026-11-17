package cn.erp.portal;

import cn.erp.reporting.ReportService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PortalStatements {
    private static final int MAX_ROWS=5_000;
    private final ReportService reports;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    PortalStatements(ReportService reports,JdbcTemplate jdbc,ObjectMapper json) {
        this.reports=reports;
        this.jdbc=jdbc;
        this.json=json;
    }

    public record Item(String businessDate,String sourceNo,String openingBalance,String debitAmount,String creditAmount,String runningBalance) {}
    public record Totals(String openingBalance,String debitAmount,String creditAmount,String closingBalance) {}
    public record Statement(String dateFrom,String dateTo,List<Item> items,Totals summary,String digest,String asOf,Receipt confirmation) {}
    public record Receipt(String id,String confirmedAt) {}
    record Confirm(LocalDate dateFrom,LocalDate dateTo,String digest) {}

    public Statement get(PortalActor actor,LocalDate from,LocalDate to) {
        var result=reports.customerStatement(actor.tenantId(),actor.companyId(),actor.partnerId(),from,to);
        if(result.items().size()>MAX_ROWS) throw PortalValues.invalid("对账明细超过 5000 行，请缩小日期范围");
        var items=result.items().stream().map(this::item).toList();
        var totals=new Totals(field(result.summary(),"openingBalance","0.00"),field(result.summary(),"debitAmount","0.00"),
                field(result.summary(),"creditAmount","0.00"),field(result.summary(),"closingBalance","0.00"));
        String first=result.summary().get("dateFrom").toString(),last=result.summary().get("dateTo").toString();
        String digest=PortalSessions.digest(json.writeValueAsString(List.of(actor.tenantId(),actor.companyId(),actor.partnerId(),first,last,items,totals)));
        return new Statement(first,last,items,totals,digest,result.asOf(),receipt(actor,first,last,digest));
    }

    @Transactional
    public Receipt confirm(PortalActor actor,Confirm input) {
        if(input.dateFrom()==null || input.dateTo()==null || input.digest()==null || !input.digest().matches("[0-9a-f]{64}"))
            throw PortalValues.invalid("请先读取指定期间的对账版本");
        var statement=get(actor,input.dateFrom(),input.dateTo());
        if(!statement.digest().equals(input.digest()))
            throw new ApiException(409,"PORTAL_STATEMENT_CHANGED","对账数据已变化，请重新读取后确认");
        if(statement.confirmation()!=null) return statement.confirmation();
        jdbc.update("""
                INSERT INTO portal_statement_acceptance(id,tenant_id,company_id,partner_id,portal_user_id,date_from,date_to,statement_digest,data_as_of)
                VALUES(?,?,?,?,?,?,?,?,?) AS accepted ON DUPLICATE KEY UPDATE id=portal_statement_acceptance.id
                """,Ids.next(),actor.tenantId(),actor.companyId(),actor.partnerId(),actor.userId(),input.dateFrom(),input.dateTo(),input.digest(),
                LocalDateTime.ofInstant(Instant.parse(statement.asOf()),ZoneOffset.UTC));
        return receipt(actor,statement.dateFrom(),statement.dateTo(),statement.digest());
    }

    private Receipt receipt(PortalActor actor,String from,String to,String digest) {
        return jdbc.query("""
                SELECT id,created_at FROM portal_statement_acceptance
                WHERE tenant_id=? AND company_id=? AND partner_id=? AND date_from=? AND date_to=? AND statement_digest=?
                """,(row,n)->new Receipt(row.getString("id"),row.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC).toString()),
                actor.tenantId(),actor.companyId(),actor.partnerId(),from,to,digest).stream().findFirst().orElse(null);
    }

    private Item item(Map<String,Object> row) {
        return new Item(field(row,"businessDate",""),field(row,"sourceNo",""),field(row,"openingBalance","0.00"),
                field(row,"debitAmount","0.00"),field(row,"creditAmount","0.00"),field(row,"runningBalance","0.00"));
    }

    private String field(Map<String,Object> data,String name,String fallback) {
        Object value=data.get(name);
        return value==null?fallback:value.toString();
    }
}
