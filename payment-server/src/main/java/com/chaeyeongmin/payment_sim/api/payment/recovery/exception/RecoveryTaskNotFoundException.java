package com.chaeyeongmin.payment_sim.api.payment.recovery.exception;

public class RecoveryTaskNotFoundException extends RuntimeException {

    public RecoveryTaskNotFoundException(Long taskId) {
        super("RECOVERY_TASK_NOT_FOUND: " + taskId);
    }
}
