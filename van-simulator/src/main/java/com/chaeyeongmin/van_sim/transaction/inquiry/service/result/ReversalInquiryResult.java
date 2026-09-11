package com.chaeyeongmin.van_sim.transaction.inquiry.service.result;

import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;

import java.time.LocalDateTime;

/** VAN reversal 원장에서 읽은 사실을 Inquiry TCP 계층으로 전달하는 조회 전용 결과다. */
public record ReversalInquiryResult(
        String vanReversalTrxId,
        String reversalPosTrx,
        VanReversalStatus status,
        String reversalApprovalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
