package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

/** Worker가 Recovery Task 한 건을 실행한 결과다. */
public enum RecoveryWorkerResultType {
    /** 현재 실행 가능한 Recovery Task가 없다. */
    NO_TASK,

    /** Handler 처리와 Recovery Task의 완료 표시가 모두 끝났다. */
    RESOLVED,

    /** 아직 복구되지 않아 다음 실행 시각까지 대기한다. */
    RETRY_WAIT,

    /** 자동 복구를 중단하고 운영자 확인을 기다린다. */
    MANUAL_REVIEW,

    /** lease 만료 또는 재claim 때문에 이번 Worker가 task 상태를 바꿀 권한을 잃었다. */
    OWNERSHIP_LOST
}
