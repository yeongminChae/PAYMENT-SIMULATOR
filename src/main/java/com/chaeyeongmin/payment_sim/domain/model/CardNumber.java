package com.chaeyeongmin.payment_sim.domain.model;

/**
 * 카드번호에서 저장/비교 가능한 최소 식별값을 추출한다.
 *
 * <p>
 * PAN 원문은 DB, 이벤트 로그, 응답 메시지에 저장하지 않는다.
 */
public record CardNumber(String value) {

    public String bin8() {
        return value.substring(0, 8);
    }

    public String last4() {
        return value.substring(value.length() - 4);
    }
}
