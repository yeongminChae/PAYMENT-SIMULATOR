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
        // VAN 내부 reversal 거래 추적키.
        String vanReversalTrxId,

        // 응답 correlation용 reversalPosTrx. same original 재응답에서도 현재 요청 값을 유지한다.
        String reversalPosTrx,

        String originalPosTrx,
        int originalAttemptSeq,
        int amount,

        // 저장된 van_reversal 원장 상태.
        VanReversalStatus reversalStatus,

        // 현재 요청이 신규 성공인지, 기존 reversal 재응답인지, 거절인지 나타내는 결과 코드.
        ReversalResultCode resultCode,

        // REVERSED일 때만 존재한다.
        String reversalApprovalNo,

        // REVERSAL_DECLINED일 때만 존재한다.
        String declineCode,

        LocalDateTime processedAt
) {
}
