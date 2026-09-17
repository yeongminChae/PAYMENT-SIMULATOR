package com.chaeyeongmin.payment_sim.api.payment.recovery.dto;

import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;

import java.time.LocalDateTime;
import java.util.List;

public record RecoveryTaskDetailResponse(
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
        LocalDateTime updatedAt,
        List<RecoveryHistoryResponse> histories
) {
}
