package com.chaeyeongmin.payment_sim.van.validate;

public enum VanValidationError {
    INVALID_REQUEST,
    INVALID_POS_TRX,
    INVALID_ATTEMPT_SEQ,
    INVALID_AMOUNT,
    INVALID_CARD,
    INVALID_PAN,
    INVALID_EXPIRY,
    INVALID_BIN,
    INVALID_LAST4;

    public String code() {
        return name();
    }
}
