package com.chaeyeongmin.payment_sim.payment.application.reversal.transaction.model;

import com.chaeyeongmin.payment_sim.payment.api.reversal.ReversalResponse;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;

public record ReversalPrepareResult(
        boolean completed,
        ReversalResponse completedResponse,
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        PaymentAttempt originalAttempt
) {

    public static ReversalPrepareResult completed(ReversalResponse response) {
        return new ReversalPrepareResult(
                true,
                response,
                response.reversalPosTrx(),
                response.originalPosTrx(),
                response.originalAttemptSeq(),
                null
        );
    }

    public static ReversalPrepareResult created(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        return new ReversalPrepareResult(
                false,
                null,
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt
        );
    }

    public boolean isCompleted() {
        return completed;
    }
}
