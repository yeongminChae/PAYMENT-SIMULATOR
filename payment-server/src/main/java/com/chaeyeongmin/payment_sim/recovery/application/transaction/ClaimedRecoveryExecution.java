package com.chaeyeongmin.payment_sim.recovery.application.transaction;

import com.chaeyeongmin.payment_sim.recovery.domain.RecoveryHistory;
import com.chaeyeongmin.payment_sim.recovery.domain.RecoveryTask;

/** 같은 transaction에서 claim된 task와 함께 생성된 실행 시작 이력이다. */
public record ClaimedRecoveryExecution(
        RecoveryTask task,
        RecoveryHistory history
) {
}
