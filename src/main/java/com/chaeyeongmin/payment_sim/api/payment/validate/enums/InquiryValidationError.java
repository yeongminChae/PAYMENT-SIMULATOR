package com.chaeyeongmin.payment_sim.api.payment.validate.enums;

public enum InquiryValidationError {
    INVALID_REQUEST,
    INVALID_POS_TRX,
    INVALID_ATTEMPT_SEQ;

    public String code() {
        return name();
    }
}
