package com.chaeyeongmin.payment_sim.common.api;

public class ErrorResponse {
    private String error;
    private String detail;

    public ErrorResponse() {
    }

    public ErrorResponse(String error, String detail) {
        this.error = error;
        this.detail = detail;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}