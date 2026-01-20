package com.chaeyeongmin.payment_sim.api.payment.dto;

public class InquiryRequest {
    private String pos_trx;
    private int attempt_seq;

    public String getPos_trx() {
        return pos_trx;
    }

    public void setPos_trx(String pos_trx) {
        this.pos_trx = pos_trx;
    }

    public int getAttempt_seq() {
        return attempt_seq;
    }

    public void setAttempt_seq(int attempt_seq) {
        this.attempt_seq = attempt_seq;
    }
}