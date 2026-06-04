package com.chaeyeongmin.payment_sim.van.client.dto;

import lombok.Builder;

/**
 * [VAN Request] 취소 요청 DTO
 *
 * <p>
 * C6: 결제서버 -> VAN 시뮬레이터 취소 요청
 *
 * <p>
 * 정책:
 * - posTrx는 이번 취소 거래번호다.
 * - originalPosTrx + originalAttemptSeq는 취소 대상 원승인 attempt를 식별한다.
 * - approvalNo는 원승인 승인번호다.
 * - amount는 MVP 기준 원승인 금액 전체취소 금액이다.
 * - vanTrxId는 원승인 VAN 추적키가 있으면 전달한다.
 * - cardLast4는 시뮬레이터 규칙 판단용으로만 사용한다.
 */
@Builder
public record VanCancelRequest(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount,
        String approvalNo,
        String vanTrxId,
        String cardLast4
) {
}