package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.identity.SecretProtector;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EInvoiceConfig {
    private final JdbcTemplate jdbc;
    private final SecretProtector protection;
    private final EInvoiceProvider provider;
    private final AuditService audit;

    EInvoiceConfig(JdbcTemplate jdbc,SecretProtector protection,EInvoiceProvider provider,AuditService audit) {
        this.jdbc=jdbc;this.protection=protection;this.provider=provider;this.audit=audit;
    }

    record Input(@Pattern(regexp="[0-9A-Z]{8,32}",message="请输入有效的销方纳税人识别号") String taxNo,
                 @Size(max=1000,message="网关密钥长度超限") String token,boolean enabled,Long version) {
        @Override public String toString() {return "数电发票服务商配置";}
    }
    record View(String provider,String taxNo,boolean enabled,boolean tokenConfigured,boolean gatewayAvailable,long version) {}
    private record Saved(String taxNo,boolean enabled,String token,long version) {}

    View get(CompanyAccess actor) {requireFull(actor);return view(stored(actor.tenantId(),actor.companyId()));}

    @Transactional
    View save(CompanyAccess actor,Input input) {
        requireFull(actor);
        jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,actor.tenantId(),actor.companyId());
        Saved prior=stored(actor.tenantId(),actor.companyId());
        if(prior==null?input.version()!=null&&input.version()!=0:input.version()==null||prior.version()!=input.version())
            throw new ApiException(409,"VERSION_CONFLICT","服务商配置已变化，请刷新重试");
        if(input.taxNo()==null)throw invalid("销方纳税人识别号必填");
        String tax=input.taxNo();
        if(prior!=null&&!tax.equals(prior.taxNo())&&Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM einv_issue_request WHERE tenant_id=? AND company_id=? AND status<>'draft')
                """,Boolean.class,actor.tenantId(),actor.companyId()))) throw invalid("已有开票历史时不能变更销方识别号");
        String cipher=prior==null?null:prior.token();
        if(input.token()!=null&&!input.token().isBlank()) {
            if(!input.token().matches("[\u0021-\u007e]{1,1000}")) throw invalid("网关密钥须为有效的可打印字符");
            cipher=protection.encrypt(actor.tenantId(),purpose(actor.companyId()),input.token());
        }
        if(cipher==null || input.enabled()&&!provider.available())throw invalid("请先配置可信网关和访问密钥");
        if(prior==null)jdbc.update("INSERT INTO einv_provider_config(tenant_id,company_id,tax_no,credentials_cipher,enabled) VALUES(?,?,?,?,?)",
                actor.tenantId(),actor.companyId(),tax,cipher,input.enabled());
        else jdbc.update("UPDATE einv_provider_config SET tax_no=?,credentials_cipher=?,enabled=?,version=version+1 WHERE tenant_id=? AND company_id=?",
                tax,cipher,input.enabled(),actor.tenantId(),actor.companyId());
        var after=get(actor);
        audit.record(actor,"einv.provider.save","company",actor.companyId(),view(prior),after);
        return after;
    }

    void test(CompanyAccess actor) {
        requireFull(actor);
        var saved=stored(actor.tenantId(),actor.companyId());
        if(saved==null||!provider.available())throw unavailable();
        provider.test(credentials(actor.tenantId(),actor.companyId(),saved));
    }

    EInvoiceProvider.Credentials credentials(long tenant,long company) {
        Saved saved=stored(tenant,company);
        if(saved==null||!saved.enabled()||!provider.available())throw unavailable();
        return credentials(tenant,company,saved);
    }

    List<Long> enabled(long tenant) {
        if(!provider.available())return List.of();
        return jdbc.queryForList("""
            SELECT p.company_id FROM einv_provider_config p JOIN company c ON c.tenant_id=p.tenant_id AND c.id=p.company_id
              JOIN tenant t ON t.id=p.tenant_id WHERE p.tenant_id=? AND p.enabled=1 AND c.status='active' AND t.status='active'
            """,Long.class,tenant);
    }

    private Saved stored(long tenant,long company) {
        return jdbc.query("SELECT tax_no,enabled,credentials_cipher,version FROM einv_provider_config WHERE tenant_id=? AND company_id=?",
                (r,n)->new Saved(r.getString(1),r.getBoolean(2),r.getString(3),r.getLong(4)),tenant,company).stream().findFirst().orElse(null);
    }

    private View view(Saved saved) {
        return saved==null?new View("gateway","",false,false,provider.available(),0):
                new View("gateway",saved.taxNo(),saved.enabled(),true,provider.available(),saved.version());
    }
    static void requireFull(CompanyAccess actor) {
        if(!actor.allWarehouses()||!actor.orgScope().allDepartments())
            throw new ApiException(403,"FORBIDDEN","此操作需要当前公司的完整仓库和部门范围");
    }
    static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    private String purpose(long company) {return "einvoice-gateway-"+company;}
    private EInvoiceProvider.Credentials credentials(long tenant,long company,Saved saved) {
        return new EInvoiceProvider.Credentials(saved.taxNo(),protection.decrypt(tenant,purpose(company),saved.token()));
    }
    private ApiException unavailable() {return new ApiException(409,"EINV_PROVIDER_NOT_ENABLED","当前公司尚未配置可用的数电发票服务商");}
}
