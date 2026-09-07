package com.chaeyeongmin.van_sim.protocol.inquiry;

/**
 * Inquiry 응답 프로토콜에 노출되는 승인 원장 상태다.
 * <p>
 * VAN 내부 원장 상태를 TCP 계약에 그대로 드러내는 enum이다.
 * Payment Server에서는 UNKNOWN을 UNKNOWN_TIMEOUT으로 해석하지만,
 * VAN 입장에서는 "원장상 최종 승인/거절을 단정할 수 없음"이라는 의미의 UNKNOWN으로 유지한다.
 */
public enum InquiryResponseStatus {
    APPROVED,
    DECLINED,
    UNKNOWN,
    CANCELLED,
    CANCEL_DECLINED
}
