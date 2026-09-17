package com.chaeyeongmin.payment_sim.api.payment.recovery.dto;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;

import java.time.LocalDateTime;

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
