package com.chaeyeongmin.payment_sim.api.postrx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * [DTO]
 * EOT 요청 DTO
 * - pos_trx: 선택값(현재는 사용 안 함 / 추후 추적·호환 목적)
 * - store_cd, biz_date, pos_no: EOT 채번에 필요한 키 값
 */
public class PosTrxEotRequest {

    @NotBlank(message = "storeCd는 필수입니다.")
    @Pattern(regexp = "^\\d{4}$", message = "storeCd는 4자리 숫자여야 입니다.")
    private String storeCd;

    @NotBlank(message = "bizDate는 필수입니다.")
    @Pattern(regexp = "^\\d{8}$", message = "bizDate는 yyyymmdd 8자리여야 합니다.")
    private String bizDate;

    @NotBlank(message = "posNo는 필수입니다.")
    @Pattern(regexp = "^\\d{4}$", message = "posNo는 4자리 숫자여야 합니다.")
    private String posNo;
    private String posTrx;   // optional, 전체 거래 번호 : 2301-20260122-9999-0012

    // JSON 역직렬화(Jackson) 용 기본 생성자
    public PosTrxEotRequest() {
    }

    // 테스트/내부 코드 편의용 생성자 (pos_trx 없이)
    public PosTrxEotRequest(String storeCd, String bizDate, String posNo) {
        this.storeCd = storeCd;
        this.bizDate = bizDate;
        this.posNo = posNo;
    }

    // (선택) pos_trx까지 포함한 생성자 - 필요할 때만 쓰기
    public PosTrxEotRequest(String posTrx, String storeCd, String bizDate, String posNo) {
        this.posTrx = posTrx;
        this.storeCd = storeCd;
        this.bizDate = bizDate;
        this.posNo = posNo;
    }

    public String getPosTrx() {
        return posTrx;
    }

    public void setPosTrx(String posTrx) {
        this.posTrx = posTrx;
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
}