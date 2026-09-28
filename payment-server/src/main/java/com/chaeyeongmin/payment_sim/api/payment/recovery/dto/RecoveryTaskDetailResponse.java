package com.chaeyeongmin.payment_sim.api.payment.recovery.dto;

import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;

import java.time.LocalDateTime;
import java.util.List;

/** Recovery Task의 현재 상태와 과거 실행 이력을 한 번에 보여주는 관리자 상세 응답이다. */
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
        /** 오래된 실행부터 정렬된 전체 실행 이력 */
        List<RecoveryHistoryResponse> histories
) {
}
