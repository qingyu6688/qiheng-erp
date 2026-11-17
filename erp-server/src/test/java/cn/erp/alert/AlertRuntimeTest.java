package cn.erp.alert;

import cn.erp.jobs.OutboxService;
import cn.erp.shared.BusinessChanged;
import cn.erp.shared.InventoryBalanceChanged;
import cn.erp.shared.ManagedJobs;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertRuntimeTest {
    @AfterEach void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void combinesOneHundredInventoryLinesAndOneBusinessCommandInTheSameOutboxSignal() {
        var outbox=mock(OutboxService.class);
        var listener=new AlertRuntime(mock(JdbcTemplate.class),mock(AlertScan.class),mock(AlertRules.class),outbox,
                new ObjectMapper(),mock(ManagedJobs.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        for (long i=1;i<=100;i++) listener.inventory(new InventoryBalanceChanged(1,2,List.of(i)));
        listener.business(new BusinessChanged(1,2,3,"shipment.sign",4));

        verifyNoInteractions(outbox);
        var hooks=TransactionSynchronizationManager.getSynchronizations();
        assertThat(hooks).hasSize(1);
        hooks.getFirst().beforeCommit(false);
        var signal=ArgumentCaptor.forClass(AlertRuntime.Signal.class);
        verify(outbox,times(1)).publish(eq(1L),eq("alert_signal"),anyLong(),eq(0),eq("alert.refresh"),signal.capture());
        assertThat(signal.getValue().companyId()).isEqualTo(2);
        assertThat(signal.getValue().codes()).containsExactly("stock_below_safety","stock_slow_moving","batch_near_expiry",
                "batch_expired","credit_exceeded","negative_margin","receivable_overdue");
    }
}
