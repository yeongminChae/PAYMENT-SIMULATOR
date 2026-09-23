package com.chaeyeongmin.payment_sim.api.payment.service.recovery.notification;

import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;

import java.time.LocalDateTime;

/**
 * MANUAL_REVIEW 알림 한 건에 담기는 정보다.
 * 운영자는 이 값으로 대상 거래, 재시도 횟수, 자동 복구를 중단한 이유를 확인한다.
 *
 * @param taskId Recovery Task ID
 * @param targetType 복구 대상 거래 종류
 * @param targetTrxNo 복구 대상 거래번호
 * @param targetAttemptSeq 승인 대상의 시도 번호; 취소·망취소는 null
 * @param originalPosTrx 원거래 번호
 * @param originalAttemptSeq 원거래의 시도 번호
 * @param retryCount 지금까지 RETRY_WAIT로 전환된 횟수
 * @param errorCode MANUAL_REVIEW로 보낸 이유
 * @param occurredAt MANUAL_REVIEW 전환 시각
 */
public record RecoveryManualReviewNotification(
        Long taskId,
        RecoveryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        String originalPosTrx,
        int originalAttemptSeq,
        int retryCount,
        String errorCode,
        LocalDateTime occurredAt
) {
}
