package com.chaeyeongmin.van_sim.transaction.approval.service.result;

import com.chaeyeongmin.van_sim.ledger.approval.status.ApprovalStatus;

import java.time.LocalDateTime;

public record ApprovalResult(
        String vanTrxId,
        String posTrx,
        int attemptSeq,
        ApprovalStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
