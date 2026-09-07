package com.chaeyeongmin.van_sim.transaction.inquiry.service.result;

import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;

import java.time.LocalDateTime;

public record CancelInquiryResult(
        String vanCancelTrxId,
        String cancelPosTrx,
        VanCancelStatus status,
        String cancelApprovalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
