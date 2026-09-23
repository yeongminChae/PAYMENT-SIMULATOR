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

    /** 선택한 상태의 Task를 조회해 관리자 목록용 요약 응답으로 변환한다. */
    public List<RecoveryTaskSummaryResponse> findTasksByStatus(RecoveryStatus status) {
        return recoveryTaskRepository.findByStatus(status).stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    /**
     * Task 한 건과 그 Task의 전체 실행 이력을 조합해 상세 응답을 만든다.
     * Task가 없으면 실행 이력을 조회하지 않고 404로 변환될 예외를 던진다.
     */
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

    /** Recovery Task를 관리자 목록에 필요한 필드만 가진 응답으로 바꾼다. */
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

    /** Worker 실행 이력을 관리자 상세 화면에 표시할 응답으로 바꾼다. */
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
