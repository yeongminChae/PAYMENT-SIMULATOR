package com.chaeyeongmin.payment_sim.api.payment.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import lombok.Builder;

/**
 * [DTO] 승인 응답(Approve) DTO
 * <p>
 * 목적:
 * - 승인 처리 결과를 클라이언트(POS)에 반환한다.
 * <p>
 * 필드(요약):
 * - posTrx     : 거래번호(EOT로 발급된 포스TR)
 * - attemptSeq : 서버가 확정한 승인 시도 번호(항상 존재)
 * - approvalNo : 승인 성공 시 발급되는 승인번호(성공일 때만)
 * - declineCode: 거절 시 사유 코드(DECLINED일 때만)
 * - cardSummary: 저장/로그 가능한 최소 카드정보(BIN/last4/brand)
 * <p>
 * 보안:
 * - PAN/expiry 등 민감정보는 포함하지 않는다.
 */

@Builder
public record ApproveResponse(
        String posTrx,
        int attemptSeq,
        PaymentFinalStatus finalStatus,
        String approvalNo,
        String declineCode,
        CardSummary cardSummary
) {

    /**
     * ApproveResponse 빌더를 "공통 필드"로 미리 채워서 반환한다.
     * <p>
     * 공통 필드(항상 채움):
     * - posTrx     : 거래번호(포스TR)
     * - attemptSeq : 승인 시도 순번
     * - cardSummary: 저장/로그 가능한 최소 카드정보(BIN/last4/brand)
     * <p>
     * 사용 의도:
     * - 케이스별 응답 생성 메소드(processing/approved/declined/unknownTimeout)에서
     * 중복 코드를 줄이고, "상태/부가필드만" 추가하도록 만든 헬퍼다.
     */
    private static ApproveResponseBuilder baseBuilder(String posTrx, int attemptSeq, CardSummary cardSummary) {
        return ApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardSummary(cardSummary);
    }

    /**
     * [A10] 처리중(PROCESSING) 응답을 생성한다.
     * <p>
     * 언제 사용하나?
     * - 동일 posTrx에 대해 DB에 attempt 레코드는 존재하지만(final_status = NULL),
     * 아직 승인 결과가 확정되지 않은 상태일 때.
     * <p>
     * 의미:
     * - "지금은 확정 결과를 줄 수 없으니, 클라이언트는 재시도/조회로 이어가라"의 최소 구현 응답.
     */
    public static ApproveResponse processing(String posTrx, int attemptSeq, CardSummary cardSummary) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.PROCESSING)
                .build();
    }

    /**
     * [A9] 승인 성공(APPROVED) 응답을 생성한다.
     * <p>
     * 언제 사용하나?
     * - DB의 attempt 레코드가 확정(APPROVED)된 경우 재응답할 때.
     * - 또는 VAN 승인 성공 후 결과 저장(A7)까지 끝난 뒤 응답할 때.
     */
    public static ApproveResponse
    approved(String posTrx, int attemptSeq, String approvalNo, CardSummary cardSummary) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo(approvalNo)
                .build();
    }

    /**
     * [A9] 승인 거절(DECLINED) 응답을 생성한다.
     * <p>
     * 언제 사용하나?
     * - DB의 attempt 레코드가 확정(DECLINED)된 경우 재응답할 때.
     * - 또는 VAN에서 거절 응답을 받은 뒤 결과 저장(A7)까지 끝난 뒤 응답할 때.
     */
    public static ApproveResponse declined(String posTrx, int attemptSeq, String declineCode, CardSummary cardSummary) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.DECLINED)
                .declineCode(declineCode)
                .build();
    }

    /**
     * [A9] 타임아웃 미확정(UNKNOWN_TIMEOUT) 응답을 생성한다.
     * <p>
     * 언제 사용하나?
     * - VAN 타임아웃 이후 후속조회(K회)로도 최종 확정이 불가능해
     * 서버가 UNKNOWN_TIMEOUT으로 확정 저장(A7)한 뒤 재응답할 때.
     * <p>
     * 비고:
     * - declineCode는 "TIMEOUT" 같은 내부 코드로 채울 수 있고,
     * 지금 단계에서는 null/고정 문자열 중 하나로 통일해도 된다.
     */
    public static ApproveResponse unknownTimeout(String posTrx, int attemptSeq, String declineCode, CardSummary cardSummary) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT)
                .declineCode(declineCode)
                .build();
    }

    /**
     * [A10] 처리중(RETRY_LATER) 응답을 생성한다.
     * <p>
     * 왜 processing()이랑 따로 두나?
     * - processing()은 "내부 상태 명칭(PROCESSING)"에 가깝고,
     * - retryLater()는 "클라이언트에게 주는 의미(재시도 요청)"를 그대로 드러내기 위함.
     * <p>
     * 현재 구현은 PROCESSING을 반환 하지만,
     * 추후 result_code를 RETRY_LATER로 분리하거나 메시지 정책이 바뀌어도
     * 서비스 코드는 retryLater() 호출 그대로 유지할 수 있다.
     */
    public static ApproveResponse retryLater(String posTrx, int attemptSeq, CardSummary cardSummary) {
        return processing(posTrx, attemptSeq, cardSummary);
    }

}
