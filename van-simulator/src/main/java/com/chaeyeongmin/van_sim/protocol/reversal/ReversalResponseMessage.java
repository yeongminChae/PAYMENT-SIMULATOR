package com.chaeyeongmin.van_sim.protocol.reversal;

import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;

/**
 * VAN 시뮬레이터가 결제 서버로 반환하는 reversal 응답 전문 모델이다.
 *
 * <p>
 * 요청 amount는 서비스 처리 검증에만 사용하고 응답 전문에는 포함하지 않는다.
 */
public record ReversalResponseMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        String vanReversalTrxId,
        VanReversalStatus reversalStatus,
        ReversalResultCode resultCode,
        String reversalApprovalNo,
        String declineCode
) {
}
