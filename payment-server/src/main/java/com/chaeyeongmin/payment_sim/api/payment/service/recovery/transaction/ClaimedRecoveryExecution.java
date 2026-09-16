package com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;

public record ClaimedRecoveryExecution(
        RecoveryTask task,
        RecoveryHistory history
) {
}
