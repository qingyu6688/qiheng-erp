package cn.erp.purchasefinance;

import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class TreasurySupport {
    private final JdbcTemplate jdbc;
    private final ReferenceAvailability references;
    TreasurySupport(JdbcTemplate jdbc,ReferenceAvailability references) {this.jdbc=jdbc;this.references=references;}

    Map<String,Object> find(CompanyAccess scope,String table,long id,boolean lock) {
        if(!Set.of("payment","account_transfer").contains(table)) {throw invalid("资金资源类型无效");}
        var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}return rows.getFirst();
    }
    boolean separate(CompanyAccess scope,Map<String,Object> source) {
        var values=jdbc.queryForList("SELECT separate_receipt_confirmer FROM company_policy WHERE tenant_id=? AND company_id=?",Integer.class,scope.tenantId(),scope.companyId());
        return (values.isEmpty()||values.getFirst()!=0)&&source.get("created_by")!=null&&id(source,"created_by")==scope.userId();
    }
    void account(CompanyAccess scope,long id) {
        references.requireCashAccount(scope,id);
        String currency=jdbc.queryForObject("SELECT currency FROM cash_account WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),id);
        if(!"CNY".equals(currency)) {throw new ApiException(422,"CURRENCY_NOT_ALLOWED","本阶段资金业务仅支持人民币账户");}
    }
    String accountName(CompanyAccess scope,Object id) {return jdbc.queryForObject("SELECT name FROM cash_account WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),id);}
    List<String> actions(CompanyAccess scope,Map<String,Object> source,String resource) {
        var actions=new ArrayList<String>();
        if("draft".equals(source.get("status"))) {add(scope,actions,resource,"update");add(scope,actions,resource,"submit");}
        if("pending_confirm".equals(source.get("status"))) {
            add(scope,actions,resource,"return");if(!separate(scope,source)) {add(scope,actions,resource,"confirm");}
        }
        return actions;
    }
    private void add(CompanyAccess scope,List<String> actions,String resource,String action) {if(scope.hasPermission(resource+":"+action)) {actions.add(action);}}
    static BigDecimal fee(String value) {return value==null||value.matches("0(?:\\.0{1,2})?")?BigDecimal.ZERO.setScale(2):amount(value);}
    static String blank(String value) {return value==null||value.isBlank()?null:value.trim();}
}
