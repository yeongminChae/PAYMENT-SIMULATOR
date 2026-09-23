package com.chaeyeongmin.payment_sim.api.payment.recovery.exception;

import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;

/** Task는 존재하지만 현재 상태가 MANUAL_REVIEW가 아니어서 requeue할 수 없을 때 발생한다. */
public class RecoveryTaskRequeueConflictException extends RuntimeException {

    /** 충돌 원인을 확인할 수 있도록 taskId와 현재 상태를 내부 메시지에 남긴다. */
    public RecoveryTaskRequeueConflictException(Long taskId, RecoveryStatus currentStatus) {
        super(
                "Recovery task cannot be requeued. "
                        + "taskId=" + taskId
                        + ", currentStatus=" + currentStatus
        );
    }
}
