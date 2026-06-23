package com.chaeyeongmin.payment_sim.domain.model;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;

/**
 * [Domain Model] PaymentAttempt (A4 분기용 조회 모델)
 * <p>
 * 목적:
 * - PAYMENT_ATTEMPT 테이블의 "처리 상태 판단에 필요한 최소 컬럼"만 담는다.
 * - 승인(Approve) A4 단계에서:
 * - finalStatus가 NULL인지 여부로 "처리중 vs 확정"을 빠르게 판단한다.
 * <p>
 * 필드 의미:
 * - finalStatus : 최종 상태 (NULL이면 처리중, 값이 있으면 확정)
 * - approvalNo  : 승인 성공 시 승인번호(확정 상태에서만 의미 있음)
 * - declineCode : 거절/실패 사유 코드(확정 상태에서만 의미 있음)
 * - cardBin     : 카드 BIN(최소정보, 민감정보(PAN) 원문 저장 금지)
 * - cardLast4   : 카드 마지막 4자리(최소정보)
 * - attempt     : attempt_seq (결제 시도 순번)
 * - amount      : 승인 금액
 * <p>
 * 비고:
 * - 이 모델은 "요청 DTO(ApproveRequest)"가 아니라 DB 조회 결과를 담는 도메인/조회 모델이다.
 * - A4 최소구현에서는 finalStatus 하나만으로도 분기 가능하다.
 */
public record PaymentAttempt(
        String finalStatus,
        String approvalNo,
        String declineCode,
        String cardBin,
        String cardLast4,
        String cardFingerprint,
        // [20260208] MyBatis 결과 매핑: ATTEMPT_SEQ -> attempt 매핑 불일치 가능성 있어 변수명 수정
        int attemptSeq,
        int amount,
        String vanTrxId
) {

    /**
     * DB FINAL_STATUS(String/null) → 도메인 enum으로 변환한다.
     * - null이면 처리중(PROCESSING)으로 간주한다.
     * - 알 수 없는 값이 들어오면 UNKNOWN_TIMEOUT 으로 안전하게 떨어 뜨린다(운영 방어).
     */
    public PaymentFinalStatus getFinalStatusEnum() {
        if (this.finalStatus == null) return PaymentFinalStatus.PROCESSING;

        try {
            return PaymentFinalStatus.valueOf(finalStatus);
        } catch (IllegalArgumentException e) {
            return PaymentFinalStatus.UNKNOWN_TIMEOUT;
        }

    }

}







