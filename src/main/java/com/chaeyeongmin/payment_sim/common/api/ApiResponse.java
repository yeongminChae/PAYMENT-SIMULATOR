package com.chaeyeongmin.payment_sim.common.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private String result_code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", null, data);
    }

    public static <T> ApiResponse<T> of(ResultCode code, String message, T data) {
        return new ApiResponse<>(code.name(), message, data);
    }

    public static <T> ApiResponse<T> of(ResultCode code, T data) {
        return new ApiResponse<>(code.name(), null, data);
    }

}
