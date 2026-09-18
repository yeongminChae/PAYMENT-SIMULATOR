package com.chaeyeongmin.payment_sim.common.exception;

import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskNotFoundException;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskRequeueConflictException;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 Controller의 예외를 공통 ApiResponse와 알맞은 HTTP 상태로 변환한다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String INVALID_REQUEST_MESSAGE = "INVALID_REQUEST";
    private static final String INTERNAL_ERROR_MESSAGE = "Unhandled error";

    /** 기존 업무 예외는 기존 규칙대로 HTTP 200과 업무 result code로 응답한다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e) {
        return ResponseEntity.ok(ApiResponse.of(e.getResultCode(), e.getMessage(), null));
    }

    /** 관리자 API에서 Recovery Task를 찾지 못하면 HTTP 404로 응답한다. */
    @ExceptionHandler(RecoveryTaskNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleRecoveryTaskNotFound(RecoveryTaskNotFoundException e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.of(ResultCode.NOT_FOUND, "RECOVERY_TASK_NOT_FOUND", null));
    }

    /** 존재하는 Task를 현재 상태 때문에 requeue할 수 없으면 HTTP 409로 응답한다. */
    @ExceptionHandler(RecoveryTaskRequeueConflictException.class)
    public ResponseEntity<ApiResponse<Object>> handleRecoveryTaskRequeueConflict(
            RecoveryTaskRequeueConflictException e
    ) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.of(ResultCode.CONFLICT, "RECOVERY_TASK_REQUEUE_CONFLICT", null));
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

    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleRouteNotFound(Exception e) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.of(ResultCode.NOT_FOUND, "NOT_FOUND", null));
    }

    // 예상하지 못한 내부 오류는 상세 원인을 숨기고 INTERNAL_ERROR로 표준화한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception e) {
        log.error("[exception][unknown] unhandled exception", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.of(ResultCode.INTERNAL_ERROR, INTERNAL_ERROR_MESSAGE, null));
    }
}
