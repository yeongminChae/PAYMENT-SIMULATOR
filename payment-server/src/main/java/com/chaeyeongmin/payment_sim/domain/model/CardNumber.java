package com.chaeyeongmin.payment_sim.domain.model;

/**
 * 카드번호에서 저장/비교 가능한 최소 식별값을 추출한다.
 *
 * <p>
 * PAN 원문은 DB, 이벤트 로그, 응답 메시지에 저장하지 않는다.
 */
public record CardNumber(String value) {

    private static final String SHORT_CARD_MASK = "****";

    public String bin8() {
        return value.substring(0, 8);
    }

    public String last4() {
        return value.substring(value.length() - 4);
    }

    /**
     * 로그나 예외 메시지에서 PAN 원문이 노출되지 않도록 마지막 4자리만 남긴다.
     */
    @Override
    public String toString() {
        return "CardNumber[value=" + mask(value) + "]";
    }

    private static String mask(String cardNo) {
        if (cardNo == null) return "null";
        if (cardNo.length() <= 4) return SHORT_CARD_MASK;

        return "*".repeat(cardNo.length() - 4)
                + cardNo.substring(cardNo.length() - 4);
    }
}
