package com.chaeyeongmin.payment_sim.common.exception;

import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("[UT-2-EXC-VALIDATION-001] BusinessException -> 예외가 가진 ResultCode/message로 응답")
    void handleBusiness_shouldReturnExceptionResultCodeAndMessage() {
        // given
        BusinessException exception = new BusinessException(
                ResultCode.INVALID,
                "INVALID_CARD"
        );

        // when
        ResponseEntity<ApiResponse<Object>> response =
                handler.handleBusiness(exception);

        // then
        ApiResponse<Object> body = response.getBody();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("INVALID", body.getResult_code());
        assertEquals("INVALID_CARD", body.getMessage());
        assertNull(body.getData());
    }

    @Test
    @DisplayName("[UT-2-EXC-NOTFOUND-001] NoHandlerFoundException -> HTTP 404와 NOT_FOUND 응답")
    void handleRouteNotFound_shouldReturnHttp404AndNotFoundResponse() {
        // given
        NoHandlerFoundException exception = new NoHandlerFoundException(
                "POST",
                "/api/v1/payments/cancel%20",
                new HttpHeaders()
        );

        // when
        ResponseEntity<ApiResponse<Object>> response =
                handler.handleRouteNotFound(exception);

        // then
        ApiResponse<Object> body = response.getBody();

        assertEquals(404, response.getStatusCode().value());
        assertEquals("NOT_FOUND", body.getResult_code());
        assertEquals("NOT_FOUND", body.getMessage());
        assertNull(body.getData());
    }

    @Test
    @DisplayName("[UT-2-EXC-UNKNOWN-001] Unknown Exception -> INTERNAL_ERROR 응답과 원인 로그")
    void handleUnknown_shouldReturnInternalErrorAndLogCause(CapturedOutput output) {
        // given
        RuntimeException exception = new RuntimeException("boom");

        // when
        ResponseEntity<ApiResponse<Object>> response =
                handler.handleUnknown(exception);

        // then
        ApiResponse<Object> body = response.getBody();

        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", body.getResult_code());
        assertEquals("Unhandled error", body.getMessage());
        assertNull(body.getData());
        assertTrue(output.getOut().contains("[exception][unknown]"));
        assertTrue(output.getOut().contains("boom"));
    }
}
