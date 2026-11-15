package cn.erp.reference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
class ReferenceService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    ReferenceService(JdbcTemplate jdbc,AuditService audit) {this.jdbc=jdbc;this.audit=audit;}

    Map<String,Object> items(CompanyAccess scope,String keyword,String cursor,int limit) {
        long last=ReferenceRows.cursor(cursor,limit);
        return ReferenceRows.page(jdbc.queryForList("""
                SELECT id,code,name,item_type,status,version FROM income_expense_item WHERE tenant_id=? AND id<?
                AND (LOCATE(?,code)>0 OR LOCATE(?,name)>0) ORDER BY id DESC LIMIT ?
                """,scope.tenantId(),last,keyword,keyword,limit+1),limit);
    }

    @Transactional
    Map<String,Object> saveItem(CompanyAccess scope,Long id,CashModels.Item input) {
        Map<String,Object> before=null;
        if (id==null) {
            id=Ids.next();
            jdbc.update("INSERT INTO income_expense_item(id,tenant_id,code,name,item_type,status,created_by) VALUES(?,?,?,?,?,?,?)",
                    id,scope.tenantId(),input.code().trim(),input.name().trim(),input.itemType(),input.status()==null?"active":input.status(),scope.userId());
        } else {
            before=ReferenceRows.one(jdbc.queryForList("SELECT id,code,name,item_type,status,version FROM income_expense_item WHERE tenant_id=? AND id=? FOR UPDATE",scope.tenantId(),id));
            ReferenceRows.version(input.version(),before);
            jdbc.update("UPDATE income_expense_item SET code=?,name=?,item_type=?,status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                    input.code().trim(),input.name().trim(),input.itemType(),input.status()==null?before.get("status"):input.status(),scope.userId(),scope.tenantId(),id);
        }
        var after=ReferenceRows.one(jdbc.queryForList("SELECT id,code,name,item_type,status,version FROM income_expense_item WHERE tenant_id=? AND id=?",scope.tenantId(),id));
        audit.record(scope,"income_expense_item.save","income_expense_item",id,before,after);return after;
    }

    Map<String,Object> periods(CompanyAccess scope,String cursor,int limit) {
        long last=ReferenceRows.cursor(cursor,limit);
        return ReferenceRows.page(jdbc.queryForList("""
                SELECT id,period,start_date,end_date,status,version FROM accounting_period
                WHERE tenant_id=? AND company_id=? AND id<? ORDER BY id DESC LIMIT ?
                """,scope.tenantId(),scope.companyId(),last,limit+1),limit);
    }

    @Transactional
    Map<String,Object> openPeriod(CompanyAccess scope,String period) {
        YearMonth month;
        try { month=YearMonth.parse(period,DateTimeFormatter.ofPattern("uuuuMM").withResolverStyle(java.time.format.ResolverStyle.STRICT)); }
        catch (DateTimeParseException ex) { throw ReferenceRows.invalid("请选择有效的年月"); }
        if (month.getYear()<1000 || month.getYear()>9999) { throw ReferenceRows.invalid("期间年份须在1000到9999之间"); }
        jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,scope.tenantId(),scope.companyId());
        var existing=jdbc.queryForList("SELECT period FROM accounting_period WHERE tenant_id=? AND company_id=? ORDER BY period DESC LIMIT 1",String.class,scope.tenantId(),scope.companyId());
        if (!existing.isEmpty() && !month.equals(YearMonth.parse(existing.getFirst(),DateTimeFormatter.ofPattern("uuuuMM")).plusMonths(1))) {
            throw new ApiException(422,"STATE_CONFLICT","请按顺序开启下一个月的期间");
        }
        long id=Ids.next();
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                id,scope.tenantId(),scope.companyId(),period,month.atDay(1),month.atEndOfMonth());
        var after=ReferenceRows.one(jdbc.queryForList("SELECT id,period,start_date,end_date,status,version FROM accounting_period WHERE id=?",id));
        audit.record(scope,"period.open","accounting_period",id,null,after);return after;
    }
}
