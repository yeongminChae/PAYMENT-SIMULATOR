package com.chaeyeongmin.payment_sim.api.postrx.dto;

public class PosTrxIssueResponse {
    private String pos_trx;

    public PosTrxIssueResponse() {
    }

    public PosTrxIssueResponse(String pos_trx) {
        this.pos_trx = pos_trx;
    }

    public String getPos_trx() {
        return pos_trx;
    }

    public void setPos_trx(String pos_trx) {
        this.pos_trx = pos_trx;
    }
}