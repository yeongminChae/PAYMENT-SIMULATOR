package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;

public record ReversalResultUpdateParam(
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        ReversalStatus reversalStatus,
        String vanReversalTrxId,
        String reversalApprovalNo,
        String declineCode
) {

    public static ReversalResultUpdateParam reversed(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String vanReversalTrxId,
            String reversalApprovalNo
    ) {
        return new ReversalResultUpdateParam(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                ReversalStatus.REVERSED,
                vanReversalTrxId,
                reversalApprovalNo,
                null
        );
    }

    public static ReversalResultUpdateParam declined(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String vanReversalTrxId,
            String declineCode
    ) {
        return new ReversalResultUpdateParam(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                ReversalStatus.REVERSAL_DECLINED,
                vanReversalTrxId,
                null,
                declineCode
        );
    }

    public String reversalStatusValue() {
        return reversalStatus.name();
    }
}
