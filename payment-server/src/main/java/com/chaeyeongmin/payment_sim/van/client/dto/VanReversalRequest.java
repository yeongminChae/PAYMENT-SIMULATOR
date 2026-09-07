package com.chaeyeongmin.payment_sim.van.client.dto;

import lombok.Builder;

/**
 * [VAN Request] Reversal 요청 DTO.
 *
 * <p>
 * Payment Server가 TCP VAN Simulator reversal 전용 mode에서 사용하는 업무 DTO다.
 */
@Builder
public record VanReversalRequest(
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount
) {
}
