package cn.erp.wms;

import cn.erp.shared.ManagedJobs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReplenishmentJobTest {
    @Test void submitsEachCompanyAndWarehousePairAsAnIndependentItem() {
        long tenant = 7L, company = 10L, otherCompany = 11L, failed = 20L, first = 21L, second = 22L;
        var jdbc = mock(JdbcTemplate.class);
        var replenishment = mock(Replenishment.class);
        var jobs = mock(ManagedJobs.class);
        var batch = mock(ManagedJobs.Batch.class, CALLS_REAL_METHODS);
        var errors = new ArrayList<Long>();
        when(jdbc.queryForList(anyString(), eq(tenant))).thenReturn(List.of(
                Map.of("company_id", company, "warehouse_id", failed),
                Map.of("company_id", company, "warehouse_id", first),
                Map.of("company_id", otherCompany, "warehouse_id", second)));
        doThrow(new IllegalStateException("补货故障注入")).when(replenishment).scheduled(tenant, company, failed);
        doAnswer(action -> {
            try {
                action.getArgument(3, Runnable.class).run();
            } catch (IllegalStateException failure) {
                errors.add(action.getArgument(2, Long.class));
            }
            return null;
        }).when(batch).run(anyLong(), anyString(), anyLong(), any(Runnable.class), any(IntPredicate.class));

        new ReplenishmentJob(jdbc, replenishment, jobs);
        var runner = ArgumentCaptor.forClass(ManagedJobs.BatchAction.class);
        verify(jobs).registerBatch(eq("wms.replenishment"), runner.capture());
        runner.getValue().run(tenant, batch);

        assertThat(errors).containsExactly(failed);
        verify(batch).run(eq(company), eq(Long.toString(failed)), eq(failed), any(Runnable.class));
        verify(batch).run(eq(company), eq(Long.toString(first)), eq(first), any(Runnable.class));
        verify(batch).run(eq(otherCompany), eq(Long.toString(second)), eq(second), any(Runnable.class));
        verify(replenishment).scheduled(tenant, company, failed);
        verify(replenishment).scheduled(tenant, company, first);
        verify(replenishment).scheduled(tenant, otherCompany, second);
        verifyNoMoreInteractions(replenishment);
    }
}
