package com.chaeyeongmin.payment_sim.common.api;

public class ApiResponse<T> {
    private String result_code;
    private String message;
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(String result_code, String message, T data) {
        this.result_code = result_code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", null, data);
    }

    public static <T> ApiResponse<T> of(ResultCode code, String message, T data) {
        return new ApiResponse<>(code.name(), message, data);
    }

    public String getResult_code() {
        return result_code;
    }

    public void setResult_code(String result_code) {
        this.result_code = result_code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}