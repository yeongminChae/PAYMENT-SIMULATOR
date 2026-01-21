package com.chaeyeongmin.payment_sim.api.payment.dto;

public class CancelRequest {
    private String current_trx_no;
    private String original_trx_no;
    private int original_attempt_seq;

    public String getCurrent_trx_no() {
        return current_trx_no;
    }

    public void setCurrent_trx_no(String current_trx_no) {
        this.current_trx_no = current_trx_no;
    }

    public String getOriginal_trx_no() {
        return original_trx_no;
    }

    public void setOriginal_trx_no(String original_trx_no) {
        this.original_trx_no = original_trx_no;
    }

    public int getOriginal_attempt_seq() {
        return original_attempt_seq;
    }

    public void setOriginal_attempt_seq(int original_attempt_seq) {
        this.original_attempt_seq = original_attempt_seq;
    }
}