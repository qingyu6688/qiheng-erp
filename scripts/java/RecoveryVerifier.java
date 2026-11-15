import cn.erp.identity.CompanyAccess;
import cn.erp.reconciliation.ReconciliationChecks;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 只读调用项目现有对账逻辑，避免恢复脚本另写一套余额算法。 */
public final class RecoveryVerifier {
    public static void main(String[] args) throws Exception {
        if(args.length!=3 || !args[1].matches("erp_restore_[a-z0-9_]{1,40}")) throw new IllegalArgumentException("需要私有配置、新恢复库和结果文件");
        var settings=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]),StandardCharsets.UTF_8)){settings.load(reader);}
        String host=settings.getProperty("DB_HOST"),port=settings.getProperty("DB_PORT");
        if(!Set.of("127.0.0.1","localhost").contains(host)||"3306".equals(port))throw new IllegalArgumentException("只允许本机独立恢复实例");
        var source=new DriverManagerDataSource("jdbc:mysql://"+host+":"+port+"/"+args[1]+"?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8",settings.getProperty("DB_USER"),settings.getProperty("DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);var checks=new ReconciliationChecks(new NamedParameterJdbcTemplate(source));
        var companies=jdbc.queryForList("SELECT j.tenant_id,j.company_id,MAX(j.id) latest FROM journal j WHERE EXISTS(SELECT 1 FROM accounting_period p WHERE p.tenant_id=j.tenant_id AND p.company_id=j.company_id) GROUP BY j.tenant_id,j.company_id ORDER BY latest DESC LIMIT 3");
        if(companies.size()<1)throw new IllegalStateException("恢复库没有可核对的真实已过账业务");
        var results=new ArrayList<String>();long transactions=0;
        for(var row:companies){
            long tenant=((Number)row.get("tenant_id")).longValue(),company=((Number)row.get("company_id")).longValue();
            var scope=new CompanyAccess(tenant,0,company,true,Set.of(),Set.of());
            long period=jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? ORDER BY end_date DESC LIMIT 1",Long.class,tenant,company);
            transactions+=jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND company_id=?",Long.class,tenant,company);
            for(String type:ReconciliationChecks.TYPES){
                var differences=checks.check(scope,type,type.equals("period_close")?period:null);
                results.add("{\"companyId\":\""+company+"\",\"check\":\""+type+"\",\"differences\":"+differences.size()+"}");
                if(!differences.isEmpty())throw new IllegalStateException("恢复后对账未通过："+type+"，公司 "+company+"，差异 "+differences.size());
            }
        }
        String result="{\"completedAt\":\""+Instant.now()+"\",\"database\":\""+args[1]+"\",\"companies\":"+companies.size()+",\"journalsChecked\":"+transactions+",\"checks\":["+String.join(",",results)+"],\"result\":\"passed\"}";
        Files.writeString(Path.of(args[2]),result,StandardCharsets.UTF_8);
        System.out.println("恢复库真实业务的八类对账全部为零，共 "+companies.size()+" 家公司、"+transactions+" 张凭证。");
    }
}
