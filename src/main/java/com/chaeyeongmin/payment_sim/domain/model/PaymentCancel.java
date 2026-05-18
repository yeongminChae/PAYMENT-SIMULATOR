package com.chaeyeongmin.payment_sim.domain.model;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;

public record PaymentCancel(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        String cancelStatus,
        String cancelApprovalNo,
        String declineCode
) {

    public CancelStatus getCancelStatusEnum() {
        return CancelStatus.valueOf(cancelStatus);
    }

}