package com.chaeyeongmin.payment_sim.api.postrx.dto;

public class PosTrxIssueRequest {
    private String store_cd;
    private String biz_date;
    private String pos_no;

    public String getStore_cd() {
        return store_cd;
    }

    public void setStore_cd(String store_cd) {
        this.store_cd = store_cd;
    }

    public String getBiz_date() {
        return biz_date;
    }

    public void setBiz_date(String biz_date) {
        this.biz_date = biz_date;
    }

    public String getPos_no() {
        return pos_no;
    }

    public void setPos_no(String pos_no) {
        this.pos_no = pos_no;
    }
}