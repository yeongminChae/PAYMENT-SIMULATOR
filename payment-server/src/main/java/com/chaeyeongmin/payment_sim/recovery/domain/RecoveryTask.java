package com.chaeyeongmin.payment_sim.recovery.domain;

import com.chaeyeongmin.payment_sim.recovery.domain.RecoveryStatus;
import com.chaeyeongmin.payment_sim.recovery.domain.RecoveryTargetType;

import java.time.LocalDateTime;

public record RecoveryTask(
        Long id,
        RecoveryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        String originalPosTrx,
        int originalAttemptSeq,
        RecoveryStatus recoveryStatus,
        int retryCount,
        LocalDateTime nextRetryAt,
        String claimToken,
        LocalDateTime leaseExpiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
