package com.chaeyeongmin.payment_sim.domain.model;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;

public record PaymentCancel(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelStatus cancelStatus,
        String cancelApprovalNo,
        String declineCode
) {
}