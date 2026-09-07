package com.chaeyeongmin.payment_sim.van.client.dto;

import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * [VAN Response] Reversal 응답 DTO.
 */
@Builder
public record VanReversalResponse(
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        VanReversalStatus reversalStatus,
        VanReversalResultCode resultCode,
        String reversalApprovalNo,
        VanDeclineCode declineCode,
        String vanReversalTrxId,
        LocalDateTime respondedAt
) {
}
