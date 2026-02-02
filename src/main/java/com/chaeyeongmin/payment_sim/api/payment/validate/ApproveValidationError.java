package com.chaeyeongmin.payment_sim.api.payment.validate;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ApproveValidationError {

    INVALID_REQUEST("INVALID_REQUEST", "요청 본문이 올바르지 않습니다."),
    INVALID_POS_TRX("INVALID_POS_TRX", "posTrx 형식이 올바르지 않습니다."),
    INVALID_AMOUNT("INVALID_AMOUNT", "amount 값이 올바르지 않습니다."),
    INVALID_CARD("INVALID_CARD", "card 정보가 올바르지 않습니다.");

    private final String code;
    private final String message;

    public String code() { return code; }
    public String message() { return message; }
}
