package com.chaeyeongmin.payment_sim.common.exception;

import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e) {
        return ResponseEntity.ok(ApiResponse.of(e.getResultCode(), e.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception e) {
        // TODO: 운영에서는 내부 에러 로깅 + 5xx 여부 정책 결정
        return ResponseEntity.ok(ApiResponse.of(ResultCode.CONFLICT, "Unhandled error", null));
    }
}