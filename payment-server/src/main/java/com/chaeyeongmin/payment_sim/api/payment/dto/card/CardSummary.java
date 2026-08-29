package com.chaeyeongmin.payment_sim.api.payment.dto.card;

/**
 * [DTO] 카드 요약 정보(민감정보 제외)
 * - cardBin  : 카드 BIN(최소정보)
 * - cardLast4: 마지막 4자리
 * - cardBrand: 가능하면 브랜드(VISA/MC 등)
 */
public record CardSummary(
        String cardBin,
        String cardLast4,
        String cardBrand
) {
}