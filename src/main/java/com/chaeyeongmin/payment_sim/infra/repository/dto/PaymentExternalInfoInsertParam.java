package com.chaeyeongmin.payment_sim.infra.repository.dto;

import java.time.LocalDateTime;

/**
 * PAYMENT_EXTERNAL_INFO 저장용 파라미터.
 *
 * <p>
 * 이 테이블은 승인 attempt에 연결된 카드/VAN/대외 식별 상세를 보관한다.
 * full PAN은 절대 담지 않고, 8자리 BIN/last4/maskedCardNo만 저장한다.
 */
public record PaymentExternalInfoInsertParam(
        String posTrx,
        int attemptSeq,
        String cardBin,
        String cardLast4,
        String maskedCardNo,
        String cardBrand,
        String cardIssuer,
        String cardCountry,
        String vanProvider,
        LocalDateTime createdAt
) {
}
