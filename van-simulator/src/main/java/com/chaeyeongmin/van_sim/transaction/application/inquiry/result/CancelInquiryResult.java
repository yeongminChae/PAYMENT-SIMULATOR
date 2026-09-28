package com.chaeyeongmin.van_sim.transaction.application.inquiry.result;

import com.chaeyeongmin.van_sim.transaction.domain.cancel.VanCancelStatus;

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
