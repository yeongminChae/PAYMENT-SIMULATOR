package com.chaeyeongmin.payment_sim.api.payment.recovery.exception;

/** 관리자 요청의 taskId에 해당하는 Recovery Task가 없을 때 발생한다. */
public class RecoveryTaskNotFoundException extends RuntimeException {

    /** 로그에서 찾지 못한 Task를 확인할 수 있도록 taskId를 내부 메시지에 남긴다. */
    public RecoveryTaskNotFoundException(Long taskId) {
        super("RECOVERY_TASK_NOT_FOUND: " + taskId);
    }
}
