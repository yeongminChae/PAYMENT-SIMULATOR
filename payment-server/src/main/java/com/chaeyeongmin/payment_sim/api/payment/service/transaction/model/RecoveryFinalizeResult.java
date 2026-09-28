package com.chaeyeongmin.payment_sim.api.payment.service.transaction.model;

public record RecoveryFinalizeResult(
        RecoveryFinalizeResultType resultType,
        String intendedStatus,
        String dbStatus
) {
}
