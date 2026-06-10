package com.chaeyeongmin.payment_sim.api.payment.dto.response;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;

/**
 * [API Response] 결제 취소 응답 DTO.
 *
 * <p>
 * 이 객체는 취소 API 응답의 data 영역 전체를 표현한다.
 * {@link CancelResultStatus}는 이 DTO 안의 cancelStatus 필드에 들어가는 "상태 값"이고,
 * CancelResponse는 거래번호, 원거래 식별자, 취소 승인번호/거절코드까지 포함한 "응답 데이터 묶음"이다.
 *
 * <p>
 * Controller는 Service가 만든 CancelResponse를 공통 {@code ApiResponse.data}에 담아 클라이언트에 내려준다.
 * 따라서 이 DTO의 필드는 내부 계산용 값이 아니라 외부 응답 계약에 포함되는 값이다.
 *
 * <p>
 * MVP 취소 정책:
 * - 취소 성공: result_code=OK, cancelStatus=CANCELLED
 * - VAN 취소 거절: result_code=CANCEL_DECLINED, cancelStatus=CANCEL_DECLINED
 * - 이미 취소 완료: result_code=ALREADY_CANCELLED, cancelStatus=ALREADY_CANCELLED
 * - 취소 불가: result_code=CANCEL_NOT_ALLOWED, cancelStatus=CANCEL_NOT_ALLOWED
 * - 취소 처리중/미확정: result_code=RETRY_LATER, cancelStatus=RETRY_LATER
 *
 * <p>
 * 참고:
 * - result_code는 {@code PaymentApiResponseMapper}가 cancelStatus를 보고 결정한다.
 * - DB 저장 상태인 {@code CancelStatus}와 API 응답 상태인 {@code CancelResultStatus}는 역할이 다르다.
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
