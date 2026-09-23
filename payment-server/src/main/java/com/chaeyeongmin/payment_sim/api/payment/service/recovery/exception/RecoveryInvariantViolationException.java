package com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception;

/**
 * Recovery가 전제로 삼은 거래 식별자나 VAN 응답 규칙이 깨졌음을 나타낸다.
 *
 * <p>자동으로 추정해서 처리하면 다른 거래를 변경할 수 있으므로 Worker는 이 예외를
 * MANUAL_REVIEW 대상으로 분류한다.
 */
public class RecoveryInvariantViolationException extends RuntimeException {

    public RecoveryInvariantViolationException(String message) {
        super(message);
    }

    public RecoveryInvariantViolationException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }

}
