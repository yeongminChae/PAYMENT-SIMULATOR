package com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin;

import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskDetailResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskSummaryResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskNotFoundException;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryAdminQueryServiceTest {

    private static final Long TASK_ID = 41L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 17, 9, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 17, 10, 0);

    private RecoveryTaskRepository taskRepository;
    private RecoveryHistoryRepository historyRepository;
    private RecoveryAdminQueryService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(RecoveryTaskRepository.class);
        historyRepository = mock(RecoveryHistoryRepository.class);
        service = new RecoveryAdminQueryService(taskRepository, historyRepository);
    }

    @Test
    void status로조회한task를_summaryResponse로변환한다() {
        RecoveryTask task = manualReviewTask();
        when(taskRepository.findByStatus(RecoveryStatus.MANUAL_REVIEW)).thenReturn(List.of(task));

        List<RecoveryTaskSummaryResponse> responses =
                service.findTasksByStatus(RecoveryStatus.MANUAL_REVIEW);

        assertThat(responses).containsExactly(new RecoveryTaskSummaryResponse(
                TASK_ID,
                RecoveryTargetType.APPROVAL,
                "ADMIN-APPROVAL-1",
                2,
                "ADMIN-APPROVAL-1",
                2,
                RecoveryStatus.MANUAL_REVIEW,
                3,
                null,
                CREATED_AT,
                UPDATED_AT
        ));
        verify(taskRepository).findByStatus(RecoveryStatus.MANUAL_REVIEW);
    }

    @Test
    void 상세조회는_task와_tryNo순서의history를조합한다() {
        RecoveryTask task = manualReviewTask();
        RecoveryHistory first = history(101L, 1, "RETRY_WAIT", "TIMEOUT");
        RecoveryHistory second = history(102L, 2, "MANUAL_REVIEW", "RETRY_EXHAUSTED");
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(historyRepository.findByRecoveryTaskId(TASK_ID)).thenReturn(List.of(first, second));

        RecoveryTaskDetailResponse response = service.findTaskDetail(TASK_ID);

        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.targetType()).isEqualTo(RecoveryTargetType.APPROVAL);
        assertThat(response.recoveryStatus()).isEqualTo(RecoveryStatus.MANUAL_REVIEW);
        assertThat(response.histories()).extracting(history -> history.tryNo())
                .containsExactly(1, 2);
        assertThat(response.histories()).extracting(history -> history.historyId())
                .containsExactly(101L, 102L);
        assertThat(response.histories().get(1).errorCode()).isEqualTo("RETRY_EXHAUSTED");
    }

    @Test
    void 존재하지않는taskId는_notFound이고_history를조회하지않는다() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findTaskDetail(TASK_ID))
                .isInstanceOf(RecoveryTaskNotFoundException.class)
                .hasMessageContaining(TASK_ID.toString());
        verify(historyRepository, never()).findByRecoveryTaskId(TASK_ID);
    }

    private RecoveryTask manualReviewTask() {
        return new RecoveryTask(
                TASK_ID,
                RecoveryTargetType.APPROVAL,
                "ADMIN-APPROVAL-1",
                2,
                "ADMIN-APPROVAL-1",
                2,
                RecoveryStatus.MANUAL_REVIEW,
                3,
                null,
                null,
                null,
                CREATED_AT,
                UPDATED_AT
        );
    }

    private RecoveryHistory history(Long historyId, int tryNo, String result, String errorCode) {
        LocalDateTime startedAt = CREATED_AT.plusMinutes(tryNo);
        return new RecoveryHistory(
                historyId,
                TASK_ID,
                tryNo,
                result,
                errorCode,
                startedAt,
                startedAt.plusSeconds(10)
        );
    }
}
