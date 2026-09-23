package com.chaeyeongmin.payment_sim.api.payment.service.recovery.batch;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorker;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResultType;
import org.junit.jupiter.api.Test;
import org.springframework.batch.repeat.RepeatStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryProcessTaskletTest {

    @Test
    void noTask가나올때까지worker를계속실행한다() throws Exception {
        RecoveryWorker worker = mock(RecoveryWorker.class);
        when(worker.executeOne()).thenReturn(
                result(RecoveryWorkerResultType.RESOLVED),
                result(RecoveryWorkerResultType.RETRY_WAIT),
                result(RecoveryWorkerResultType.NO_TASK)
        );
        RecoveryProcessTasklet tasklet = new RecoveryProcessTasklet(worker);

        RepeatStatus status = tasklet.execute(null, null);

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        verify(worker, times(3)).executeOne();
    }

    @Test
    void worker의runtimeException을그대로전파한다() {
        RecoveryWorker worker = mock(RecoveryWorker.class);
        RuntimeException exception = new RuntimeException("worker failed");
        when(worker.executeOne()).thenThrow(exception);
        RecoveryProcessTasklet tasklet = new RecoveryProcessTasklet(worker);

        assertThatThrownBy(() -> tasklet.execute(null, null)).isSameAs(exception);
    }

    private static RecoveryWorkerResult result(RecoveryWorkerResultType resultType) {
        return new RecoveryWorkerResult(resultType, null, null);
    }
}
