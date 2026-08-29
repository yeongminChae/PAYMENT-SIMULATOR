package com.chaeyeongmin.payment_sim.api.postrx.dto;

/**
 * [DTO]
 * EOT 응답 DTO
 * - pos_trx: 선택값(현재는 사용 안 함 / 추후 추적·호환 목적)
 * - store_cd, biz_date, pos_no: EOT 채번에 필요한 키 값
 */
public class PosTrxEotResponse {
    private String storeCd;
    private String bizDate;
    private String posNo;
    private String nextPosTrx; // 전체 거래 번호 : 2301-20260122-9999-0012

    public PosTrxEotResponse() {
    }

    public PosTrxEotResponse(String storeCd, String bizDate, String posNo, String nextPosTrx) {
        this.storeCd = storeCd;
        this.bizDate = bizDate;
        this.posNo = posNo;
        this.nextPosTrx = nextPosTrx;
    }

    public String getStoreCd() {
        return storeCd;
    }

    public void setStoreCd(String storeCd) {
        this.storeCd = storeCd;
    }

    public String getBizDate() {
        return bizDate;
    }

    public void setBizDate(String bizDate) {
        this.bizDate = bizDate;
    }

    public String getPosNo() {
        return posNo;
    }

    public void setPosNo(String posNo) {
        this.posNo = posNo;
    }

    public String getNextPosTrx() {
        return nextPosTrx;
    }

    public void setNextPosTrx(String nextPosTrx) {
        this.nextPosTrx = nextPosTrx;
    }
}