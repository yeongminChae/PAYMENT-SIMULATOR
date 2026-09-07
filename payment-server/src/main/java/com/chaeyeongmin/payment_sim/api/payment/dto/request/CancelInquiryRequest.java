package com.chaeyeongmin.payment_sim.api.payment.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 취소 조회 API 요청 DTO.
 *
 * <p>
 * posTrx는 PAYMENT_CANCEL.CURRENT_TRX_NO, 즉 취소 요청 자체의 거래번호다.
 * 원승인 거래번호로 조회하지 않는 이유는 하나의 원승인에 대한 취소 복구 판단을
 * "이번 취소 요청 row" 기준으로 고정하기 위해서다.
 */
public record CancelInquiryRequest(
        @NotBlank
        String posTrx
) {
}
