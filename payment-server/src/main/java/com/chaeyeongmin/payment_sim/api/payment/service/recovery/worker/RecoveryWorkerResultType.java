package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

/** Worker가 Recovery Task 한 건을 실행한 결과다. */
public enum RecoveryWorkerResultType {
    /** 현재 실행 가능한 Recovery Task가 없다. */
    NO_TASK,

    /** Handler 처리와 Recovery Task의 완료 표시가 모두 끝났다. */
    RESOLVED,

    /** Handler는 완료됐지만 lease 만료 또는 재claim으로 task 상태 변경 권한을 잃었다. */
    OWNERSHIP_LOST,

    /** VAN 또는 DB에서 아직 terminal 사실을 확인하지 못했다. */
    STILL_UNRESOLVED,

    /** VAN의 terminal 사실과 DB에 이미 저장된 terminal 사실이 충돌한다. */
    TERMINAL_CONFLICT,

    /** Recovery Task가 가리키는 실제 거래를 DB에서 찾지 못했다. */
    TARGET_NOT_FOUND
}
