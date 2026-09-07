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
    ALREADY_REVERSED,
    CANCEL_DECLINED,
    CANCEL_NOT_ALLOWED,
    REVERSAL_DECLINED,
    REVERSAL_NOT_ALLOWED,
    INTERNAL_ERROR
}
