package com.chaeyeongmin.payment_sim.api.payment.service.transaction.model;

public enum RecoveryFinalizeResultType {

    /** 이번 복구 작업이 미확정 거래를 최종 상태로 변경했다. */
    APPLIED,

    /** 다른 흐름이 이미 같은 최종 상태로 처리했다. */
    ALREADY_CONSISTENT,

    /** VAN에서 확인한 최종 상태와 DB의 최종 상태가 서로 다르다. */
    TERMINAL_CONFLICT,

    /** 원장을 갱신하지 못했고 다시 조회한 상태도 아직 미확정이다. */
    STILL_UNRESOLVED,

    /** 복구할 거래를 DB에서 찾을 수 없다. */
    TARGET_NOT_FOUND,

    /** 현재 Worker가 작업 소유권을 잃어 원장을 변경하지 않았다. */
    OWNERSHIP_LOST
}
