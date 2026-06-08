package com.chaeyeongmin.payment_sim.common.api;

public enum ResultCode {
    OK,
    DECLINED,
    UNKNOWN_TIMEOUT,
    RETRY_LATER,
    CONFLICT,
    INVALID,
    NOT_FOUND,
    ALREADY_CANCELLED,
    // 20260608 추가
    CANCEL_DECLINED,
    CANCEL_NOT_ALLOWED
}
