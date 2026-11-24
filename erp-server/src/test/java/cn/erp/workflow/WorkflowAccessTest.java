package cn.erp.workflow;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.QuotationScopePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowAccessTest {
    private final CompanyAccess actor=new CompanyAccess(1,2,3,true,Set.of(),
            Set.of("sales_quote:view","refund_request:view","sales_return:view","cost:view"));

    @Test void legacyStandaloneHelperKeepsRefundVisibilityButDoesNotGrantUnwiredQuotationAccess() {
        String predicate=new WorkflowAccess().business(actor,"i",false);
        assertThat(predicate).contains("i.business_type='refund_request'", "i.business_type='sales_return'")
                .doesNotContain("i.business_type='sales_quote'");
    }

    @Test void wiredHelperUsesCurrentActorQuotationPredicateAndDoesNotDropOtherBusinessTypes() {
        var port=mock(QuotationScopePort.class);
        when(port.condition(actor,"h")).thenReturn(" AND h.customer_id=41");
        var factory=new StaticListableBeanFactory();factory.addBean("quotations",port);
        var access=new WorkflowAccess();access.quotations(factory.getBeanProvider(QuotationScopePort.class));
        String predicate=access.business(actor,"i",false);
        assertThat(predicate).contains("i.business_type='sales_quote'", "AND h.customer_id=41", "i.business_type='refund_request'");
        verify(port).condition(actor,"h");
    }
}
