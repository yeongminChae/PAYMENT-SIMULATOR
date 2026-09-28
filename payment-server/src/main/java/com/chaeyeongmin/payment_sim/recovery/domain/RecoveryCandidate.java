package com.chaeyeongmin.payment_sim.recovery.domain;

import com.chaeyeongmin.payment_sim.recovery.domain.RecoveryTargetType;

import java.time.LocalDateTime;

public record RecoveryCandidate(
        RecoveryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        String originalPosTrx,
        int originalAttemptSeq,
        LocalDateTime candidateSince
) {
}
