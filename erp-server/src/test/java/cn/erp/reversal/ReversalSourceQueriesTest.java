package cn.erp.reversal;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReversalSourceQueriesTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ReversalSourceQueries queries=new ReversalSourceQueries(jdbc);
    private final CompanyAccess scope=new CompanyAccess(1,2,3,true,Set.of(),
            Set.of("shipment:view","price_adjustment:view","sales_price_adjustment:view","cost:view"));

    @Test void rejectsUnknownSourceBeforeAccessingDatabase() {
        assertThatThrownBy(()->queries.list(scope,"shipment JOIN sys_user ON 1=1",null,20,""))
                .isInstanceOf(ApiException.class).hasMessage("来源类型或单号筛选无效");
        verifyNoInteractions(jdbc);
    }

    @Test void bindsKeywordAndCompanyInsteadOfInterpolatingThem() {
        String keyword="' OR 1=1 --";
        queries.list(scope,"shipment",99L,20,keyword);
        var sql=ArgumentCaptor.forClass(String.class);
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(),args.capture());
        assertThat(sql.getValue()).contains("FROM shipment d", "LOCATE(?,d.shipment_no)")
                .doesNotContain(keyword);
        assertThat(args.getValue()).containsExactly(1L,3L,99L,keyword,"shipment",21);
    }

    @Test void salesAdjustmentUsesSharedTableAndKeepsSalesIsolation() {
        queries.list(scope,"sales_price_adjustment",null,20,"");
        var sql=ArgumentCaptor.forClass(String.class);
        var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(),args.capture());
        assertThat(sql.getValue()).contains("FROM price_adjustment d", "d.kind='sales'")
                .doesNotContain("FROM sales_price_adjustment", "d.kind='purchase'");
        assertThat(args.getValue()).contains("sales_price_adjustment");
    }
}
