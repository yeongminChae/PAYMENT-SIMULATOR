package com.chaeyeongmin.van_sim.transaction.reversal.service.result;

import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;

import java.time.LocalDateTime;

/**
 * Reversal 처리 결과.
 *
 * <p>
 * reversalStatus는 van_reversal 저장 상태이고,
 * resultCode는 현재 요청에 대한 응답 의미다.
 */
public record ReversalResult(
        String vanReversalTrxId,
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount,
        VanReversalStatus reversalStatus,
        ReversalResultCode resultCode,
        String reversalApprovalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
