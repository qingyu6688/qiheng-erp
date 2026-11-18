package cn.erp.crm;

import cn.erp.shared.ManagedJobs;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class CrmPoolJobsTest {
    @Test void submitsEachCompanyAsAnIndependentItemAndPreservesBatchCursor() {
        long tenant = 7L, failing = 10L, first = 11L, second = 12L;
        var jdbc = mock(JdbcTemplate.class);
        var settings = mock(CrmPoolSettings.class);
        var jobs = mock(ManagedJobs.class);
        var batch = mock(ManagedJobs.Batch.class, CALLS_REAL_METHODS);
        var errors = new ArrayList<Long>();
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(tenant))).thenReturn(List.of(failing, first, second));
        when(settings.reclaimBatch(tenant, failing, 0)).thenThrow(new IllegalStateException("回收故障注入"));
        when(settings.reclaimBatch(tenant, first, 0)).thenReturn(99L);
        doAnswer(action -> {
            try {
                action.getArgument(3, Runnable.class).run();
            } catch (IllegalStateException failure) {
                errors.add(action.getArgument(0, Long.class));
            }
            return null;
        }).when(batch).run(anyLong(), anyString(), anyLong(), any(Runnable.class), any(IntPredicate.class));

        new CrmPoolJobs(jdbc, settings).managed(jobs);
        var runner = ArgumentCaptor.forClass(ManagedJobs.BatchAction.class);
        verify(jobs).registerBatch(eq("crm.pool_reclaim"), runner.capture());
        runner.getValue().run(tenant, batch);

        assertThat(errors).containsExactly(failing);
        for (long company : List.of(failing, first, second))
            verify(batch).run(eq(company), eq(Long.toString(company)), eq(company), any(Runnable.class));
        var work = inOrder(settings);
        work.verify(settings).reclaimBatch(tenant, failing, 0);
        work.verify(settings).reclaimBatch(tenant, first, 0);
        work.verify(settings).reclaimBatch(tenant, first, 99L);
        work.verify(settings).reclaimBatch(tenant, second, 0);
    }
}
