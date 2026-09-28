package com.chaeyeongmin.payment_sim.recovery.application.transaction.model;

public record RecoveryFinalizeResult(
        RecoveryFinalizeResultType resultType,
        String intendedStatus,
        String dbStatus
) {
}
