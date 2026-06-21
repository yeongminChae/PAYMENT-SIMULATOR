package com.chaeyeongmin.payment_sim.api.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * [DTO] 취소 요청
 *
 * <p>
 * - posTrx             : 이번 취소 거래번호(현거래번호/포스TR)
 * - originalPosTrx     : 취소 대상 원승인 거래번호
 * - originalAttemptSeq : 취소 대상 원승인 attemptSeq
 * - cardNo             : 취소 요청 카드번호(원승인 카드와의 비교를 위한 입력)
 *
 * <p>
 * 정책:
 * - 취소 API는 거래번호를 새로 발급하지 않는다.
 * - posTrx는 POS가 전달한 취소 거래번호를 그대로 사용한다.
 * - originalPosTrx + originalAttemptSeq로 원승인 attempt를 식별한다.
 * - cardNo 원문은 저장/로그/응답에 사용하지 않고, 추후 BIN 8자리와 last4 비교에만 사용한다.
 */
public record CancelRequest(
        @NotBlank
        String posTrx,

        @NotBlank
        String originalPosTrx,

        @Positive
        int originalAttemptSeq,

        @NotBlank
        String cardNo
) {

    private static final String SHORT_CARD_MASK = "****";

    /**
     * 요청 객체가 문자열로 출력되더라도 카드번호 원문은 노출하지 않는다.
     */
    @Override
    public String toString() {
        return "CancelRequest[" +
                "posTrx=" + posTrx +
                ", originalPosTrx=" + originalPosTrx +
                ", originalAttemptSeq=" + originalAttemptSeq +
                ", cardNo=" + maskCardNo(cardNo) +
                "]";
    }

    private static String maskCardNo(String cardNo) {
        if (cardNo == null) return "null";
        if (cardNo.length() <= 4) return SHORT_CARD_MASK;

        return "*".repeat(cardNo.length() - 4)
                + cardNo.substring(cardNo.length() - 4);
    }
}
