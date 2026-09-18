package com.chaeyeongmin.payment_sim.api.payment.recovery.dto;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;

import java.time.LocalDateTime;

/** 관리자 목록과 requeue 결과에 사용하는 Recovery Task 요약 응답이다. */
public record RecoveryTaskSummaryResponse(
        Long taskId,
        RecoveryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        String originalPosTrx,
        int originalAttemptSeq,
        RecoveryStatus recoveryStatus,
        int retryCount,
        LocalDateTime nextRetryAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /** 저장소에서 읽은 Recovery Task를 API 응답으로 변환한다. */
    public static RecoveryTaskSummaryResponse from(RecoveryTask task) {
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

}
