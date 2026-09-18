package com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction;

/** 한 번의 Recovery Worker 실행이 어떤 결과로 끝났는지 나타낸다. */
public enum RecoveryHistoryResult {
    /** Handler가 복구 완료를 확인해 Task도 정상 종료됐다. */
    RESOLVED,

    /** 아직 해결되지 않아 다음 자동 복구 시각을 기다린다. */
    RETRY_WAIT,

    /** 자동 판단을 중단하고 운영자의 확인이 필요하다. */
    MANUAL_REVIEW,

    /** 처리 중 claimToken 또는 lease가 바뀌어 Task 상태를 변경하지 못했다. */
    OWNERSHIP_LOST,

    /** 예상하지 못한 오류를 기록했으며 Task 상태는 판단하지 않았다. */
    UNKNOWN_FAILURE
}
