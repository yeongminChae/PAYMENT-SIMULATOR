package com.chaeyeongmin.payment_sim.domain.model;

/**
 * 승인 저장 시점에 사용할 카드 식별 결과.
 *
 * <p>
 * PAN 원문은 담지 않고, 8자리 BIN/last4와 BIN_CATALOG에서 식별한 부가정보만 담는다.
 * API 응답에 issuer/country/vanProvider를 노출하지 않더라도,
 * PAYMENT_EXTERNAL_INFO에는 대외 식별 추적을 위해 저장한다.
 */
public record CardIdentity(
        String cardBin,
        String cardLast4,
        String brand,
        String issuer,
        String country,
        String vanProvider
) {
    private static final String UNKNOWN = "UNKNOWN";
    private static final String MOCK_VAN = "MOCK_VAN";

    /** 미등록 또는 비활성 BIN일 때 저장할 기본 식별값. */
    public static CardIdentity unknown(String cardBin, String cardLast4) {
        return new CardIdentity(cardBin, cardLast4, UNKNOWN, UNKNOWN, UNKNOWN, MOCK_VAN);
    }

    /** ACTIVE 8자리 BIN_CATALOG row를 내부 저장 정책에 맞는 식별값으로 변환한다. */
    public static CardIdentity from(String cardBin, String cardLast4, BinCatalog catalog) {
        return new CardIdentity(
                cardBin,
                cardLast4,
                valueOrUnknown(catalog.brand()),
                valueOrUnknown(catalog.issuer()),
                valueOrUnknown(catalog.country()),
                MOCK_VAN
        );
    }

    private static String valueOrUnknown(String value) {
        if (value == null || value.isBlank()) return UNKNOWN;
        return value;
    }
}
