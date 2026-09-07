package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry;

/**
 * VAN TCP Inquiry 응답의 status 필드를 Payment Server 쪽에서 역직렬화하기 위한 protocol enum이다.
 * <p>
 * 이 enum은 Payment의 업무 상태가 아니라 VAN TCP 계약의 문자열 값이다.
 * 따라서 UNKNOWN은 Payment DB의 UNKNOWN_TIMEOUT과 같은 의미로 해석되지만,
 * TCP JSON에서는 VAN Simulator가 보내는 값 그대로 UNKNOWN으로 유지한다.
 */
public enum VanInquiryStatus {
    APPROVED,
    DECLINED,
    UNKNOWN,
    CANCELLED,
    CANCEL_DECLINED
}
