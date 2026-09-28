package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

/** Recovery Handler가 이번 실행에서 확인한 업무 결과다. */
public enum RecoveryHandlerResultType {
    /** 거래가 이미 끝났거나 VAN에서 확인한 최종 결과를 DB에 반영했다. */
    RESOLVED,

    /** VAN과 DB를 확인했지만 아직 최종 결과를 정할 수 없다. */
    STILL_UNRESOLVED,

    /** VAN의 최종 결과와 DB의 최종 결과가 서로 다르다. */
    TERMINAL_CONFLICT,

    /** Task가 가리키는 거래를 DB에서 찾을 수 없다. */
    TARGET_NOT_FOUND,

    /** lease 만료나 다른 Worker의 재선점으로 현재 Worker가 원장을 변경할 권한을 잃었다. */
    OWNERSHIP_LOST
}
