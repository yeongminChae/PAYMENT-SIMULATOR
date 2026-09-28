package com.chaeyeongmin.payment_sim.payment.api.cancel;

public enum CancelValidationError {
    INVALID_REQUEST,
    INVALID_POS_TRX,
    INVALID_ORIGINAL_POS_TRX,
    INVALID_ORIGINAL_ATTEMPT_SEQ,
    INVALID_CARD;

    public String code() {
        return name();
    }
}