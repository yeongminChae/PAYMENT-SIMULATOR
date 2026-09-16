package com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception;

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
