package com.chaeyeongmin.payment_sim.domain.policy;

public enum RecoveryStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    RESOLVED,
    MANUAL_REVIEW
}
