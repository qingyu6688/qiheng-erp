package cn.erp.coderule;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.DocumentNumbers;
import cn.erp.shared.Ids;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class CodeRulesIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired CodeRules rules;
    @Autowired DocumentNumbers numbers;
    private final long tenant=Ids.next(), foreign=Ids.next();
    private final long a=Ids.next(), b=Ids.next(), f=Ids.next();

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'取号租户'),(?,?,'其他租户')",tenant,"CR"+tenant,foreign,"CR"+foreign);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','甲'),(?,?,'B','乙'),(?,?,'F','丙')",a,tenant,b,tenant,f,foreign);
    }

    @AfterEach void clean() {
        for(var name:List.of("code_rule_seq","code_rule","document_sequence","audit_log","company"))
            jdbc.update("DELETE FROM "+name+" WHERE tenant_id IN (?,?)",tenant,foreign);
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)",tenant,foreign);
    }

    @Test void previewDoesNotAllocateAndEachCompanyAndMonthHasItsOwnSequence() throws Exception {
        var actor=actor(tenant,a);
        var global=input("0","G-");
        rules.save(actor,global);
        rules.save(actor,input(Long.toString(a),"A-"));
        LocalDate first=LocalDate.of(2026,11,17), next=first.plusMonths(1);
        var preview=new CodeRules.Preview(input(Long.toString(a),"A-"),first,null);
        assertThat(rules.preview(actor,preview)).isEqualTo("A-2026110001");
        assertThat(rules.preview(actor,preview)).isEqualTo("A-2026110001");
        assertThat(numbers.next(actor(tenant,b),"SO",first)).isEqualTo("G-2026110001");
        assertThat(numbers.next(actor(foreign,f),"SO",first)).isEqualTo("SO-F-202611-000001");
        List<Future<String>> results=new ArrayList<>();
        try(var pool=Executors.newFixedThreadPool(6)) {
            for(int i=0;i<24;i++)results.add(pool.submit(()->numbers.next(actor,"SO",first)));
            var values=new HashSet<String>();
            for(var result:results) values.add(result.get(20,TimeUnit.SECONDS));
            assertThat(values).hasSize(24).contains("A-2026110001","A-2026110024");
        }
        assertThat(numbers.next(actor,"SO",next)).isEqualTo("A-2026120001");
        assertThatThrownBy(()->rules.save(actor,input(Long.toString(b),"BAD-")))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(403));
        assertThat(rules.list(actor(foreign,f))).isEmpty();
    }

    @Test void rejectsImpossibleLengthsAndResetWithoutMatchingDate() {
        var actor=actor(tenant,a);
        var serial=new CodePattern.Segment("serial",null,9);
        assertThatThrownBy(()->rules.save(actor,new CodeRules.Input("0","document:sales_order",
                List.of(new CodePattern.Segment("dept_code",null,32),serial),"none",true,null)))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(400));
        assertThatThrownBy(()->rules.save(actor,new CodeRules.Input("0","document:sales_order",
                List.of(serial),"day",true,null))).hasMessageContaining("重置周期");
    }

    private CodeRules.Input input(String company,String prefix) {
        return new CodeRules.Input(company,"document:sales_order",List.of(new CodePattern.Segment("literal",prefix,null),
                new CodePattern.Segment("date","yyyyMM",null),new CodePattern.Segment("serial",null,4)),"month",true,null);
    }

    private CompanyAccess actor(long tenant,long company) {
        return new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("code_rule:manage"));
    }
}
