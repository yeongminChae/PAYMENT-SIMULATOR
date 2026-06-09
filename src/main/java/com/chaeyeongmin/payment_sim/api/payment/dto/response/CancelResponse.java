package com.chaeyeongmin.payment_sim.api.payment.dto.response;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;

/**
 * [API Response] 결제 취소 응답 DTO
 *
 * <p>
 * MVP 취소 정책:
 * - 취소 성공: OK + CANCELLED
 * - VAN 취소 거절: DECLINED + CANCEL_DECLINED
 * - 이미 취소 완료: ALREADY_CANCELLED + CANCELLED
 * - 취소 불가: CANCEL_NOT_ALLOWED
 * - 취소 처리중/미확정: RETRY_LATER + PENDING
 *
 * <p>
 * 참고:
 * - resultCode는 공통 응답 래퍼 또는 상위 정책에서 다룰 수 있다.
 * - 이 DTO는 취소 업무 결과에 필요한 최소 필드만 담는다.
 */
public record CancelResponse(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelResultStatus cancelStatus,
        String cancelApprovalNo,
        String declineCode
) {

    public static CancelResponse cancelled(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String cancelApprovalNo
    ) {
        return new CancelResponse(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelResultStatus.CANCELLED,
                cancelApprovalNo,
                null
        );
    }

    public static CancelResponse declined(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String declineCode
    ) {
        return new CancelResponse(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelResultStatus.CANCEL_DECLINED,
                null,
                declineCode
        );
    }

    public static CancelResponse alreadyCancelled(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String cancelApprovalNo
    ) {
        return new CancelResponse(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelResultStatus.ALREADY_CANCELLED,
                cancelApprovalNo,
                null
        );
    }

    public static CancelResponse cancelNotAllowed(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String declineCode
    ) {
        return new CancelResponse(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelResultStatus.CANCEL_NOT_ALLOWED,
                null,
                declineCode
        );
    }

    public static CancelResponse retryLater(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        return new CancelResponse(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelResultStatus.RETRY_LATER,
                null,
                null
        );
    }
    
}