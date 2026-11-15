package cn.erp.reference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
class CashService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private static final String SELECT = """
            SELECT a.id,a.code,a.name,a.account_type,a.bank_name,a.account_no,a.currency,a.status,a.version,b.balance
            FROM cash_account a JOIN cash_account_balance b ON b.tenant_id=a.tenant_id
              AND b.company_id=a.company_id AND b.cash_account_id=a.id
            """;

    CashService(JdbcTemplate jdbc,AuditService audit) { this.jdbc=jdbc;this.audit=audit; }

    Map<String,Object> list(CompanyAccess scope,String keyword,String cursor,int limit) {
        long last=ReferenceRows.cursor(cursor,limit);
        var page=ReferenceRows.page(jdbc.queryForList(SELECT+"""
                WHERE a.tenant_id=? AND a.company_id=? AND a.id<? AND (LOCATE(?,a.code)>0 OR LOCATE(?,a.name)>0)
                ORDER BY a.id DESC LIMIT ?
                """,scope.tenantId(),scope.companyId(),last,keyword,keyword,limit+1),limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)page.get("items");
        items.forEach(row -> visible(scope,row));
        return page;
    }

    Map<String,Object> get(CompanyAccess scope,long id,boolean lock) {
        var row=ReferenceRows.one(jdbc.queryForList(SELECT+" WHERE a.tenant_id=? AND a.company_id=? AND a.id=?"+(lock?" FOR UPDATE":""),
                scope.tenantId(),scope.companyId(),id));
        visible(scope,row); return row;
    }

    private void visible(CompanyAccess scope,Map<String,Object> row) {
        if (!scope.hasPermission("cash_account_no:view") && row.get("accountNo")!=null) {
            String raw=row.get("accountNo").toString();
            row.put("accountNo","****"+raw.substring(Math.max(0,raw.length()-4)));
        }
        row.put("allowedActions",scope.hasPermission("cash_account:update")?List.of("update","active".equals(row.get("status"))?"disable":"enable"):List.of());
    }

    private void checkAccountNo(CompanyAccess scope,String no) {
        if (no!=null && (!scope.hasPermission("cash_account_no:view") || no.startsWith("****"))) {
            throw new ApiException(403,"FORBIDDEN","请使用有账号查看权限的用户维护完整银行账号");
        }
    }

    @Transactional
    Map<String,Object> create(CompanyAccess scope,CashModels.Account input) {
        checkAccountNo(scope,input.accountNo());
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type,bank_name,account_no,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),scope.companyId(),input.code().trim(),input.name().trim(),input.accountType(),input.bankName(),input.accountNo(),scope.userId(),scope.userId());
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",
                Ids.next(),scope.tenantId(),scope.companyId(),id);
        return changed(scope,id,"cash_account.create",null);
    }

    @Transactional
    Map<String,Object> update(CompanyAccess scope,long id,CashModels.Account input) {
        checkAccountNo(scope,input.accountNo());
        var before=get(scope,id,true);ReferenceRows.version(input.version(),before);
        jdbc.update("""
                UPDATE cash_account SET code=?,name=?,account_type=?,bank_name=?,
                account_no=CASE WHEN ?='' THEN NULL ELSE COALESCE(?,account_no) END,version=version+1,updated_by=?
                WHERE tenant_id=? AND company_id=? AND id=?
                """,input.code().trim(),input.name().trim(),input.accountType(),input.bankName(),input.accountNo(),input.accountNo(),scope.userId(),scope.tenantId(),scope.companyId(),id);
        return changed(scope,id,"cash_account.update",before);
    }

    @Transactional
    Map<String,Object> status(CompanyAccess scope,long id,String action,long version) {
        var before=get(scope,id,true);ReferenceRows.version(version,before);
        jdbc.update("UPDATE cash_account SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                ReferenceRows.status(action),scope.userId(),scope.tenantId(),scope.companyId(),id);
        return changed(scope,id,"cash_account."+action,before);
    }

    private Map<String,Object> changed(CompanyAccess scope,long id,String action,Map<String,Object> before) {
        var after=get(scope,id,false);
        var safeBefore=before==null?null:new LinkedHashMap<>(before);
        if (safeBefore!=null) {safeBefore.remove("accountNo");}
        var safeAfter=new LinkedHashMap<>(after);safeAfter.remove("accountNo");
        audit.record(scope,action,"cash_account",id,safeBefore,safeAfter);
        return after;
    }
}
