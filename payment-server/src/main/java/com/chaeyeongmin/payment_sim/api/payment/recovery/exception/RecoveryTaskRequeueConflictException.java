package com.chaeyeongmin.payment_sim.api.payment.recovery.exception;

import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;

public class RecoveryTaskRequeueConflictException extends RuntimeException {

    public RecoveryTaskRequeueConflictException(Long taskId, RecoveryStatus currentStatus) {
        super(
                "Recovery task cannot be requeued. "
                        + "taskId=" + taskId
                        + ", currentStatus=" + currentStatus
        );
    }
}
