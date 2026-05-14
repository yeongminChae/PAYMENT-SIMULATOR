package com.chaeyeongmin.payment_sim.van.dto;

import lombok.Builder;

/**
 * [VAN Request] VAN 조회 요청
 *
 * MVP에서는 실제 VAN 저장소가 없으므로,
 * 결제서버 DB에 저장된 attempt 정보를 바탕으로 VAN 시뮬레이터가 결과를 판단한다.
 *
 * 포함 정보:
 * - posTrx/attemptSeq : 조회 대상 식별
 * - vanTrxId          : VAN 추적키
 * - cardLast4         : 시뮬레이터 규칙 판단용
 */
@Builder
public record VanInquiryRequest(
        String posTrx,
        int attemptSeq,
        String vanTrxId,
        String cardLast4
) {
}
