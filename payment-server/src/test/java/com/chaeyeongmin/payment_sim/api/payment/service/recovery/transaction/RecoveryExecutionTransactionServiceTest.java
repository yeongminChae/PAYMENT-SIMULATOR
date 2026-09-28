package com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResultType;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryExecutionTransactionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 10, 0);
    private static final LocalDateTime LEASE_EXPIRES_AT = NOW.plusMinutes(5);

    private RecoveryTaskRepository taskRepository;
    private RecoveryHistoryRepository historyRepository;
    private RecoveryExecutionTransactionService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(RecoveryTaskRepository.class);
        historyRepository = mock(RecoveryHistoryRepository.class);
        service = new RecoveryExecutionTransactionService(taskRepository, historyRepository);
    }

    @Test
    void claim할Task가없으면_empty이고_history를생성하지않는다() {
        when(taskRepository.claimNext("worker-1", NOW, LEASE_EXPIRES_AT))
                .thenReturn(Optional.empty());

        Optional<ClaimedRecoveryExecution> result = service.claimAndStart(
                "worker-1",
                NOW,
                LEASE_EXPIRES_AT
        );

        assertThat(result).isEmpty();
        verify(historyRepository, never()).nextTryNo(org.mockito.ArgumentMatchers.anyLong());
        verify(historyRepository, never()).insertStarted(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void claim성공시_nextTryNo로_startedHistory를생성해함께반환한다() {
        RecoveryTask task = task();
        RecoveryHistory history = new RecoveryHistory(11L, task.id(), 3, null, null, NOW, null);
        when(taskRepository.claimNext("worker-1", NOW, LEASE_EXPIRES_AT))
                .thenReturn(Optional.of(task));
        when(historyRepository.nextTryNo(task.id())).thenReturn(3);
        when(historyRepository.insertStarted(task.id(), 3, NOW)).thenReturn(history);

        ClaimedRecoveryExecution result = service.claimAndStart(
                "worker-1",
                NOW,
                LEASE_EXPIRES_AT
        ).orElseThrow();

        assertThat(result.task()).isSameAs(task);
        assertThat(result.history()).isSameAs(history);
        InOrder order = inOrder(taskRepository, historyRepository);
        order.verify(taskRepository).claimNext("worker-1", NOW, LEASE_EXPIRES_AT);
        order.verify(historyRepository).nextTryNo(task.id());
        order.verify(historyRepository).insertStarted(task.id(), 3, NOW);
    }

    @Test
    void resolve는_task와_history를함께종료한다() {
        when(taskRepository.markResolved(1L, "worker-1", NOW)).thenReturn(1);
        when(historyRepository.finish(11L, RecoveryHistoryResult.RESOLVED, null, NOW)).thenReturn(1);

        assertThat(service.resolve(1L, 11L, "worker-1", NOW))
                .isEqualTo(RecoveryWorkerResultType.RESOLVED);

        InOrder order = inOrder(taskRepository, historyRepository);
        order.verify(taskRepository).markResolved(1L, "worker-1", NOW);
        order.verify(historyRepository).finish(11L, RecoveryHistoryResult.RESOLVED, null, NOW);
    }

    @Test
    void retryWait는_task와_history를함께종료한다() {
        LocalDateTime nextRetryAt = NOW.plusMinutes(1);
        when(taskRepository.markRetryWait(1L, "worker-1", NOW, nextRetryAt)).thenReturn(1);
        when(historyRepository.finish(11L, RecoveryHistoryResult.RETRY_WAIT, "VAN_GATEWAY_TIMEOUT", NOW))
                .thenReturn(1);

        assertThat(service.retryWait(1L, 11L, "worker-1", NOW, nextRetryAt, "VAN_GATEWAY_TIMEOUT"))
                .isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
    }

    @Test
    void manualReview는_task와_history를함께종료한다() {
        when(taskRepository.markManualReview(1L, "worker-1", NOW)).thenReturn(1);
        when(historyRepository.finish(11L, RecoveryHistoryResult.MANUAL_REVIEW, "RETRY_EXHAUSTED", NOW))
                .thenReturn(1);

        assertThat(service.manualReview(1L, 11L, "worker-1", NOW, "RETRY_EXHAUSTED"))
                .isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
    }

    @Test
    void transitionUpdate가0이면_history만_ownershipLost로종료한다() {
        when(taskRepository.markResolved(1L, "worker-1", NOW)).thenReturn(0);
        when(historyRepository.finish(11L, RecoveryHistoryResult.OWNERSHIP_LOST, null, NOW)).thenReturn(1);

        assertThat(service.resolve(1L, 11L, "worker-1", NOW))
                .isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
    }

    @Test
    void retryWaitUpdate가0이면_history만_ownershipLost로종료한다() {
        LocalDateTime nextRetryAt = NOW.plusMinutes(1);
        when(taskRepository.markRetryWait(1L, "worker-1", NOW, nextRetryAt)).thenReturn(0);
        when(historyRepository.finish(11L, RecoveryHistoryResult.OWNERSHIP_LOST, null, NOW)).thenReturn(1);

        assertThat(service.retryWait(1L, 11L, "worker-1", NOW, nextRetryAt, null))
                .isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
    }

    @Test
    void manualReviewUpdate가0이면_history만_ownershipLost로종료한다() {
        when(taskRepository.markManualReview(1L, "worker-1", NOW)).thenReturn(0);
        when(historyRepository.finish(11L, RecoveryHistoryResult.OWNERSHIP_LOST, null, NOW)).thenReturn(1);

        assertThat(service.manualReview(1L, 11L, "worker-1", NOW, "ignored"))
                .isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
    }

    @Test
    void unknownFailure는_task를변경하지않고_history만종료한다() {
        when(historyRepository.finish(11L, RecoveryHistoryResult.UNKNOWN_FAILURE, "NullPointerException", NOW))
                .thenReturn(1);

        service.unknownFailure(11L, "NullPointerException", NOW);

        verify(historyRepository).finish(11L, RecoveryHistoryResult.UNKNOWN_FAILURE, "NullPointerException", NOW);
        verify(taskRepository, never()).markResolved(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(taskRepository, never()).markRetryWait(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(taskRepository, never()).markManualReview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void historyFinish가정확히1건이아니면실패한다(int updated) {
        when(taskRepository.markResolved(1L, "worker-1", NOW)).thenReturn(1);
        when(historyRepository.finish(11L, RecoveryHistoryResult.RESOLVED, null, NOW)).thenReturn(updated);

        assertThatThrownBy(() -> service.resolve(1L, 11L, "worker-1", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery history finish failed: 11");
    }

    private RecoveryTask task() {
        return new RecoveryTask(
                1L,
                RecoveryTargetType.APPROVAL,
                "R6P9-5-TASK",
                1,
                "R6P9-5-ORIGINAL",
                1,
                RecoveryStatus.RUNNING,
                0,
                null,
                "worker-1",
                LEASE_EXPIRES_AT,
                NOW.minusMinutes(1),
                NOW
        );
    }
}
