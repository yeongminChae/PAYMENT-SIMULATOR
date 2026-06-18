package com.chaeyeongmin.payment_sim.domain.policy;

/**
 * PAYMENT_EVENT_LOG에 저장하는 업무 이벤트 종류.
 *
 * <p>
 * 1차 구현은 운영 추적과 정합성 분석 목적의 구조화 로그만 남기며,
 * PAN/CVC/전문 원문 같은 민감정보 이벤트는 정의하지 않는다.
 */
public enum PaymentEventType {
    APPROVE_ATTEMPT_CREATED,
    APPROVE_VAN_REQUESTED,
    APPROVE_VAN_RESULT_RECEIVED,
    APPROVE_FINALIZED,
    APPROVE_REUSED,
    APPROVE_CONFLICT,
    APPROVE_UNKNOWN_TIMEOUT,

    CANCEL_PENDING_CREATED,
    CANCEL_VAN_REQUESTED,
    CANCEL_VAN_RESULT_RECEIVED,
    CANCEL_FINALIZED,
    CANCEL_REUSED_BY_ORIGINAL,
    CANCEL_CONFLICT,
    CANCEL_NOT_ALLOWED
}
