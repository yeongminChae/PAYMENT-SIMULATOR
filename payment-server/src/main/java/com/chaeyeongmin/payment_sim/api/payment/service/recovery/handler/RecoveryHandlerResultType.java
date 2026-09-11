package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

/**
 * Handler가 반환하는 거래 복구 결과다. RecoveryStatus와 달리 이번 처리 시도의 업무 결과만 표현한다.
 */
public enum RecoveryHandlerResultType {
    /** DB가 이미 terminal이거나, VAN terminal 사실을 DB에 적용 또는 동일 사실로 확인했다. */
    RESOLVED,

    /** VAN에서도 확정 사실을 얻지 못했거나 conditional finalization 뒤 DB가 여전히 unresolved다. */
    STILL_UNRESOLVED,

    /** VAN이 확인한 terminal 사실과 DB에 저장된 terminal 사실이 서로 다르다. */
    TERMINAL_CONFLICT,

    /** task가 가리키는 대상 거래가 선행 또는 finalization 재조회 시점에 존재하지 않는다. */
    TARGET_NOT_FOUND
}
