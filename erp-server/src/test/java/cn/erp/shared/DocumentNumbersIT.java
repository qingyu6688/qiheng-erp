package cn.erp.shared;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class DocumentNumbersIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentNumbers numbers;
    @Autowired PlatformTransactionManager transactions;
    private long tenant;
    private final List<CompanyAccess> scopes=new ArrayList<>();

    @BeforeEach
    void prepare() {
        tenant=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"NUM"+tenant,"取号测试");
        for(String code:List.of("A","B")) {
            long id=Ids.next();
            jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",id,tenant,code,code);
            scopes.add(new CompanyAccess(tenant,Ids.next(),id,true,Set.of(),Set.of()));
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM document_sequence WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM company WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void B19ConcurrentCompanyAndTypeSequencesAreUniqueAndSurviveBusinessRollback() throws Exception {
        Set<String> values=ConcurrentHashMap.newKeySet();
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<String>> jobs=new ArrayList<>();
            for(int i=0;i<160;i++) {
                int index=i;
                jobs.add(()->numbers.next(scopes.get(index%2),index%4<2?"SO":"PO",YearMonth.of(2026,11)));
            }
            for(var future:pool.invokeAll(jobs)) {values.add(future.get(20,TimeUnit.SECONDS));}
        }
        assertThat(values).hasSize(160);
        assertThat(values).allMatch(value->value.matches("(SO|PO)-[AB]-202611-[0-9]{6}"));
        String[] rolled=new String[1];
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            rolled[0]=numbers.next(scopes.getFirst(),"SO",YearMonth.of(2026,12));status.setRollbackOnly();
        });
        assertThat(numbers.next(scopes.getFirst(),"SO",YearMonth.of(2026,12))).isNotEqualTo(rolled[0]);
        jdbc.update("UPDATE document_sequence SET next_value=1000000 WHERE tenant_id=? AND company_id=? AND document_type='SO' AND period='202612'",tenant,scopes.getFirst().companyId());
        assertThatThrownBy(()->numbers.next(scopes.getFirst(),"SO",YearMonth.of(2026,12)))
            .isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.getErrorCode()).isEqualTo("SEQUENCE_EXHAUSTED"));
    }
}
