package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

/** Worker가 Recovery Task 한 건을 실행한 결과다. */
public enum RecoveryWorkerResultType {
    /** 현재 실행 가능한 Recovery Task가 없다. */
    NO_TASK,

    /** Handler 처리와 Recovery Task의 완료 표시가 모두 끝났다. */
    RESOLVED,

    RETRY_WAIT,

    MANUAL_REVIEW,

    /** Handler는 완료됐지만 lease 만료 또는 재claim으로 task 상태 변경 권한을 잃었다. */
    OWNERSHIP_LOST
}
