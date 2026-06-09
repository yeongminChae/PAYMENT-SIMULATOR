package com.chaeyeongmin.payment_sim.api.payment.dto.enums;

/**
 * 취소 API 응답에 내려가는 cancelStatus 타입.
 *
 * <p>
 * DB 저장 상태가 아니라 클라이언트에 전달할 취소 결과 상태를 표현한다.
 */
public enum CancelResultStatus {
    /**
     * 이번 요청으로 취소가 성공 확정됨.
     */
    CANCELLED,

    /**
     * 기존 취소 row가 이미 취소 완료 상태임.
     */
    ALREADY_CANCELLED,

    /**
     * 원거래 상태 등 정책상 취소할 수 없음.
     */
    CANCEL_NOT_ALLOWED,

    /**
     * 취소 요청은 접수됐지만 결과가 아직 미확정임.
     */
    RETRY_LATER,

    /**
     * VAN 또는 취소 정책에 의해 취소가 거절됨.
     */
    CANCEL_DECLINED
}
