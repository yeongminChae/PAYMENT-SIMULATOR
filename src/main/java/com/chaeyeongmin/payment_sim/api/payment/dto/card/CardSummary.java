package com.chaeyeongmin.payment_sim.api.payment.dto.card;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * [DTO] 카드 요약 정보(민감정보 제외)
 * - cardBin  : 카드 BIN(최소정보)
 * - cardLast4: 마지막 4자리
 * - cardBrand: 가능하면 브랜드(VISA/MC 등)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CardSummary {
    private String cardBin;
    private String cardLast4;
    private String cardBrand;
}
