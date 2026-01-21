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
    CANCEL_NOT_ALLOWED
}