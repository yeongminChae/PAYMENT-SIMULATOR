package com.chaeyeongmin.payment_sim.domain.model;

import java.time.LocalDateTime;

public record RecoveryHistory(
        Long id,
        Long recoveryTaskId,
        int tryNo,
        String result,
        String errorCode,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
