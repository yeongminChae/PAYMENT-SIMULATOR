package com.chaeyeongmin.payment_sim.common.exception;

import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String INVALID_REQUEST_MESSAGE = "INVALID_REQUEST";
    private static final String INTERNAL_ERROR_MESSAGE = "Unhandled error";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e) {
        return ResponseEntity.ok(ApiResponse.of(e.getResultCode(), e.getMessage(), null));
    }

    // Bean Validation 원문 메시지는 rejected value를 포함할 수 있어 외부 응답은 고정 코드로 제한한다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        return ResponseEntity.ok(ApiResponse.of(ResultCode.INVALID, INVALID_REQUEST_MESSAGE, null));
    }

    // 수동 검증 외의 IllegalArgumentException도 사용자 입력 오류로 보고 민감정보 없는 메시지만 반환한다.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.ok(ApiResponse.of(ResultCode.INVALID, INVALID_REQUEST_MESSAGE, null));
    }

    // 예상하지 못한 내부 오류는 상세 원인을 숨기고 INTERNAL_ERROR로 표준화한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception e) {
        return ResponseEntity.ok(ApiResponse.of(ResultCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, null));
    }

}
