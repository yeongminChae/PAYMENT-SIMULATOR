package com.chaeyeongmin.payment_sim.api.payment.recovery.dto;

import java.time.LocalDateTime;

public record RecoveryHistoryResponse(
        Long historyId,
        int tryNo,
        String result,
        String errorCode,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
}
