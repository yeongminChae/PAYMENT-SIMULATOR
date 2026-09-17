package com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin;

import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryHistoryResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskDetailResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskSummaryResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskNotFoundException;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 관리자 Recovery 화면에 필요한 읽기 전용 조회를 조합한다. */
@Service
@RequiredArgsConstructor
public class RecoveryAdminQueryService {

    private final RecoveryTaskRepository recoveryTaskRepository;
    private final RecoveryHistoryRepository recoveryHistoryRepository;

    public List<RecoveryTaskSummaryResponse> findTasksByStatus(RecoveryStatus status) {
        return recoveryTaskRepository.findByStatus(status).stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    public RecoveryTaskDetailResponse findTaskDetail(Long taskId) {
        RecoveryTask task = recoveryTaskRepository.findById(taskId)
                .orElseThrow(() -> new RecoveryTaskNotFoundException(taskId));
        List<RecoveryHistoryResponse> histories = recoveryHistoryRepository
                .findByRecoveryTaskId(taskId)
                .stream()
                .map(this::toHistoryResponse)
                .toList();

        return new RecoveryTaskDetailResponse(
                task.id(),
                task.targetType(),
                task.targetTrxNo(),
                task.targetAttemptSeq(),
                task.originalPosTrx(),
                task.originalAttemptSeq(),
                task.recoveryStatus(),
                task.retryCount(),
                task.nextRetryAt(),
                task.createdAt(),
                task.updatedAt(),
                histories
        );
    }

    private RecoveryTaskSummaryResponse toSummaryResponse(RecoveryTask task) {
        return new RecoveryTaskSummaryResponse(
                task.id(),
                task.targetType(),
                task.targetTrxNo(),
                task.targetAttemptSeq(),
                task.originalPosTrx(),
                task.originalAttemptSeq(),
                task.recoveryStatus(),
                task.retryCount(),
                task.nextRetryAt(),
                task.createdAt(),
                task.updatedAt()
        );
    }

    private RecoveryHistoryResponse toHistoryResponse(RecoveryHistory history) {
        return new RecoveryHistoryResponse(
                history.id(),
                history.tryNo(),
                history.result(),
                history.errorCode(),
                history.startedAt(),
                history.finishedAt()
        );
    }
}
