package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;

/**
 * PAYMENT_EVENT_LOG insert 전용 파라미터.
 *
 * <p>
 * 이벤트 로그에는 운영 추적에 필요한 식별자/상태 코드만 담고,
 * PAN, CVC, Track, EMV, 전문 원문 전체는 절대 포함하지 않는다.
 */
public record PaymentEventLogInsertParam(
        PaymentEventType eventType,
        String posTrx,
        Integer attemptSeq,
        String currentTrxNo,
        String originalPosTrx,
        Integer originalAttemptSeq,
        String resultCode,
        String statusSnapshot,
        String vanTrxId,
        String approvalNo,
        String declineCode,
        String correlationId,
        String note
) {
}
