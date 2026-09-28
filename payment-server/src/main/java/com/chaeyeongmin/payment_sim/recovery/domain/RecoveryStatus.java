package com.chaeyeongmin.payment_sim.recovery.domain;

public enum RecoveryStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    RESOLVED,
    MANUAL_REVIEW
}
