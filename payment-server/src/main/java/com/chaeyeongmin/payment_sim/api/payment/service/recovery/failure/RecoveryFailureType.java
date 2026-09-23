package com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure;

public enum RecoveryFailureType {
    /** 일시적 실패로 판단되어 자동 재시도할 수 있다. */
    RETRYABLE,

    /** 자동 판단을 중단하고 운영자 확인이 필요하다. */
    MANUAL_REVIEW,

    /** Recovery가 의미를 아는 실패가 아니므로 상위로 다시 던져야 한다. */
    UNKNOWN
}
