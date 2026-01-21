package com.chaeyeongmin.payment_sim.common.exception;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;

public class ErrorCodeMapper {
    private ErrorCodeMapper() {
    }

    public static ResultCode map(Exception e) {
        // TODO: 예외 타입별 매핑 규칙 확정 후 구현
        return ResultCode.CONFLICT;
    }
}