package com.chaeyeongmin.payment_sim.infra.repository.dto;

public record CancelInsertParam(
        String posTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        String cancelStatus
) {
    public static CancelInsertParam pending(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        return new CancelInsertParam(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                "PENDING"
        );
    }
}
