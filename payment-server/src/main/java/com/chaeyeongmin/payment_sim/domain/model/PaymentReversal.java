package com.chaeyeongmin.payment_sim.domain.model;

import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;

public record PaymentReversal(
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount,
        ReversalStatus reversalStatus,
        String vanReversalTrxId,
        String reversalApprovalNo,
        String declineCode
) {
}
