package com.chaeyeongmin.payment_sim.api.postrx.dto;

/**
 * [DTO]
 * EOT 요청 DTO
 * - pos_trx: 선택값(현재는 사용 안 함 / 추후 추적·호환 목적)
 * - store_cd, biz_date, pos_no: EOT 채번에 필요한 키 값
 */
public class PosTrxEotRequest {

    private String pos_trx;   // optional
    private String store_cd;
    private String biz_date;
    private String pos_no;

    // JSON 역직렬화(Jackson) 용 기본 생성자
    public PosTrxEotRequest() {
    }

    // 테스트/내부 코드 편의용 생성자 (pos_trx 없이)
    public PosTrxEotRequest(String store_cd, String biz_date, String pos_no) {
        this.store_cd = store_cd;
        this.biz_date = biz_date;
        this.pos_no = pos_no;
    }

    // (선택) pos_trx까지 포함한 생성자 - 필요할 때만 쓰기
    public PosTrxEotRequest(String pos_trx, String store_cd, String biz_date, String pos_no) {
        this.pos_trx = pos_trx;
        this.store_cd = store_cd;
        this.biz_date = biz_date;
        this.pos_no = pos_no;
    }

    public String getPos_trx() {
        return pos_trx;
    }

    public void setPos_trx(String pos_trx) {
        this.pos_trx = pos_trx;
    }

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