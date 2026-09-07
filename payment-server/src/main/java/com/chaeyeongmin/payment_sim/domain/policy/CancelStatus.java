package com.chaeyeongmin.payment_sim.domain.policy;

/**
 * PAYMENT_CANCEL row의 내부 처리 상태.
 *
 * <p>
 * UNKNOWN_TIMEOUT은 취소 요청이 VAN에 도달했을 가능성이 있어 성공/거절을 단정할 수 없는 상태다.
 * 이 상태의 기존 row가 있으면 중복 취소를 막기 위해 VAN을 다시 호출하지 않고 retryLater로 응답한다.
 */
public enum CancelStatus {
    /**
     * 취소 요청을 접수했고 VAN 최종 응답을 아직 DB에 확정하지 못한 상태.
     */
    PENDING,

    /**
     * VAN 취소 성공이 확정되어 취소 승인번호까지 저장된 상태.
     */
    CANCELLED,

    /**
     * VAN 또는 정책상 취소 거절이 확정되어 거절 코드가 저장된 상태.
     */
    CANCEL_DECLINED,

    /**
     * VAN 호출 timeout으로 외부 처리 여부를 알 수 없어 후속 재조회/재시도가 필요한 상태.
     */
    UNKNOWN_TIMEOUT
}
