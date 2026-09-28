package com.chaeyeongmin.van_sim.transaction.inquiry.service.result;

import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;

import java.time.LocalDateTime;

/** VAN 망취소 원장에서 읽은 사실을 상위 계층으로 전달하는 서비스 조회 결과다. */
public record ReversalInquiryResult(
        String vanReversalTrxId,
        String reversalPosTrx,
        VanReversalStatus status,
        String reversalApprovalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
