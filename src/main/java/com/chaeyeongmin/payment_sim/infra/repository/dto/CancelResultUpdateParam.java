package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;

public record CancelResultUpdateParam(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        CancelStatus cancelStatus,
        String cancelApprovalNo,
        String declineCode
) {
    public static CancelResultUpdateParam cancelled(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String cancelApprovalNo
    ) {
        return new CancelResultUpdateParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.CANCELLED,
                cancelApprovalNo,
                null
        );
    }

    public static CancelResultUpdateParam declined(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String declineCode
    ) {
        return new CancelResultUpdateParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                CancelStatus.CANCEL_DECLINED,
                null,
                declineCode
        );
    }
}