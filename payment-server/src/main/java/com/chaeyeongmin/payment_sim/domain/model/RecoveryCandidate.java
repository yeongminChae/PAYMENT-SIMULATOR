package com.chaeyeongmin.payment_sim.domain.model;

import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;

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
