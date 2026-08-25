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

    /**
     * 승인 이벤트용 생성자.
     *
     * <p>
     * 승인 이벤트는 현재 승인 attempt(posTrx/attemptSeq)가 주 식별자다.
     * 취소 원거래 컬럼(originalPosTrx/originalAttemptSeq)은 의도적으로 비워 둔다.
     */
    public static PaymentEventLogInsertParam approval(
            PaymentEventType eventType,
            String posTrx,
            int attemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        return new PaymentEventLogInsertParam(
                eventType,
                posTrx,
                attemptSeq,
                null,
                null,
                null,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                null,
                note
        );
    }

    /**
     * 취소 이벤트용 생성자.
     *
     * <p>
     * 취소 이벤트는 현재 취소 거래번호(posTrx)와 원승인 거래(originalPosTrx/originalAttemptSeq)를 함께 남긴다.
     * 승인 attemptSeq/currentTrxNo/correlationId는 현재 정책에서 사용하지 않으므로 null로 고정한다.
     */
    public static PaymentEventLogInsertParam cancel(
            PaymentEventType eventType,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        return new PaymentEventLogInsertParam(
                eventType,
                posTrx,
                null,
                null,
                originalPosTrx,
                originalAttemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                null,
                note
        );
    }
}
