package com.chaeyeongmin.payment_sim.common.exception;

import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
