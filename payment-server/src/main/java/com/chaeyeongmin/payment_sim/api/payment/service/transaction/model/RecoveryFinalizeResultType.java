package com.chaeyeongmin.payment_sim.api.payment.service.transaction.model;

public enum RecoveryFinalizeResultType {

    // 이번 recovery가 실제 unresolved row를 terminal로 변경함
    APPLIED,

    // 다른 흐름이 먼저 같은 terminal fact로 확정해놓음
    ALREADY_CONSISTENT,

    // DB와 VAN이 서로 다른 terminal fact를 주장함
    TERMINAL_CONFLICT,

    // update는 실패했고 reread 결과도 아직 unresolved
    STILL_UNRESOLVED,

    // 대상 row 자체가 사라짐
    TARGET_NOT_FOUND
}
