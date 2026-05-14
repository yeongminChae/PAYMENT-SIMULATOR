package com.chaeyeongmin.payment_sim.api.payment.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import lombok.Builder;

/**
 * [API Response] 결제 조회 응답
 *
 * 응답 상태:
 * - APPROVED        : 조회 결과 승인 확정
 * - DECLINED        : 조회 결과 거절 확정
 * - UNKNOWN_TIMEOUT : 여전히 결과 미확정
 * - PROCESSING      : 아직 처리중, 잠시 후 재조회 필요
 */
@Builder
public record InquiryResponse(
        String posTrx,
        int attemptSeq,
        PaymentFinalStatus finalStatus,
        String approvalNo,
        String declineCode,
        CardSummary cardSummary
) {

    private static InquiryResponseBuilder baseBuilder(
            String posTrx,
            int attemptSeq,
            CardSummary cardSummary
    ) {
        return InquiryResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardSummary(cardSummary);
    }

    /**
     * Q7: 조회로 승인 확정
     */
    public static InquiryResponse approved(
            String posTrx,
            int attemptSeq,
            String approvalNo,
            CardSummary cardSummary
    ) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo(approvalNo)
                .declineCode(null)
                .build();
    }

    /**
     * Q7: 조회로 거절 확정
     */
    public static InquiryResponse declined(
            String posTrx,
            int attemptSeq,
            String declineCode,
            CardSummary cardSummary
    ) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.DECLINED)
                .approvalNo(null)
                .declineCode(declineCode)
                .build();
    }

    /**
     * Q8: 조회했지만 여전히 미확정
     */
    public static InquiryResponse unknownTimeout(
            String posTrx,
            int attemptSeq,
            String declineCode,
            CardSummary cardSummary
    ) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT)
                .approvalNo(null)
                .declineCode(declineCode)
                .build();
    }

    /**
     * Q10: 아직 승인 처리중이므로 잠시 후 재조회 필요
     *
     * 주의:
     * - DB에 PROCESSING을 저장하는 것이 아니라,
     *   DB의 FINAL_STATUS == null 을 응답에서 PROCESSING으로 표현하는 용도다.
     */
    public static InquiryResponse retryLater(
            String posTrx,
            int attemptSeq,
            CardSummary cardSummary
    ) {
        return baseBuilder(posTrx, attemptSeq, cardSummary)
                .finalStatus(PaymentFinalStatus.PROCESSING)
                .approvalNo(null)
                .declineCode(null)
                .build();
    }

}