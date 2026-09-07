package com.chaeyeongmin.payment_sim.infra.repository.dto;

import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;

public record ReversalInsertParam(
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount,
        ReversalStatus reversalStatus
) {

    public static ReversalInsertParam pending(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            int amount
    ) {
        return new ReversalInsertParam(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                amount,
                ReversalStatus.PENDING
        );
    }

    public String reversalStatusValue() {
        return reversalStatus.name();
    }
}
