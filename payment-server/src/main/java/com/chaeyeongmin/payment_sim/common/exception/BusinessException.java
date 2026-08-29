package com.chaeyeongmin.payment_sim.common.exception;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;

public class BusinessException extends RuntimeException {
    private final ResultCode resultCode;

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}