package com.chaeyeongmin.payment_sim.payment.domain.reversal;

import com.chaeyeongmin.payment_sim.payment.domain.reversal.ReversalStatus;

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
