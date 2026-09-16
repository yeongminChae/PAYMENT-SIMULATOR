package com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction;

public enum RecoveryHistoryResult {
    RESOLVED,
    RETRY_WAIT,
    MANUAL_REVIEW,
    OWNERSHIP_LOST,
    UNKNOWN_FAILURE
}
