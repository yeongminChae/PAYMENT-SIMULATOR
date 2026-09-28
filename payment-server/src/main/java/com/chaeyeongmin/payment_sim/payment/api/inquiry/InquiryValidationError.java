package com.chaeyeongmin.payment_sim.payment.api.inquiry;

public enum InquiryValidationError {
    INVALID_REQUEST,
    INVALID_POS_TRX,
    INVALID_ATTEMPT_SEQ;

    public String code() {
        return name();
    }
}
