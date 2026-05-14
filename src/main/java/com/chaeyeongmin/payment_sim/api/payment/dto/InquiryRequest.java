package com.chaeyeongmin.payment_sim.api.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * [API Request] 결제 조회 요청
 *
 * 조회(Inquiry)는 (posTrx, attemptSeq) 기준으로 특정 결제시도(attempt)를 조회한다.
 *
 * MVP 정책:
 * - inquiry는 UNKNOWN_TIMEOUT 상태의 결제시도를 확정하기 위한 용도
 * - 일반 거래 검색/목록 조회는 MVP 범위에서 제외
 */
public record InquiryRequest(
        /**
         * 거래번호(포스TR)
         * 예: 2376-20260215-9991-0101
         */
        @NotBlank
        String posTrx,

        /**
         * 결제 시도 순번
         * - 1 이상의 값만 허용
         */
        @Positive
        int attemptSeq
) {

}