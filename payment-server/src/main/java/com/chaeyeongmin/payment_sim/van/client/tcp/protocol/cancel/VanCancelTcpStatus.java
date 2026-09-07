package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel;

/**
 * TCP Cancel 응답에 노출되는 VAN 취소 원장 상태다.
 *
 * <p>
 * Payment domain의 CancelStatus와 이름이 같더라도 TCP JSON 계약을 표현하는 별도 enum으로 둔다.
 */
public enum VanCancelTcpStatus {
    // 취소가 정상 완료된 VAN 원장 상태.
    CANCELLED,
    // 원승인 없음, 원승인 미승인, payload 불일치 등으로 취소가 거절된 상태.
    CANCEL_DECLINED
}
