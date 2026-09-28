package com.chaeyeongmin.payment_sim.payment.domain.cancel;

import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;

public record PaymentCancel(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelStatus cancelStatus,
        String vanCancelTrxId,
        String cancelApprovalNo,
        String declineCode
) {
}
