package com.chaeyeongmin.payment_sim.api.payment.dto.card;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * [DTO] 카드 입력(CardInput)
 * <p>
 * 목적:
 * - 승인 요청에서 카드 유효성 검증 및 VAN 시뮬레이터 룰 판단을 위해 사용
 * <p>
 * 필드:
 * - pan        : 13~19자리 숫자 + Luhn 체크 대상 (민감정보)
 * - expiryYyMm : YYMM (예: 2601)
 * <p>
 * 보안:
 * - pan은 절대 로그/DB에 원문 저장 금지
 */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CardInput {
    private String pan;
    private String expiryYyMm;

    //    public void setPan(String pan) {
//        this.pan = pan;
//    }

//    public void setExpiry_yy_mm(String expiry_yy_mm) {
//        this.expiry_yy_mm = expiry_yy_mm;
//    }
}