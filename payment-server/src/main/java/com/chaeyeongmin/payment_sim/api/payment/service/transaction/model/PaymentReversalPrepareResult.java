package com.chaeyeongmin.payment_sim.api.payment.service.transaction.model;

import com.chaeyeongmin.payment_sim.api.payment.dto.response.ReversalResponse;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;

public record PaymentReversalPrepareResult(
        boolean completed,
        ReversalResponse completedResponse,
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        PaymentAttempt originalAttempt
) {

    public static PaymentReversalPrepareResult completed(ReversalResponse response) {
        return new PaymentReversalPrepareResult(
                true,
                response,
                response.reversalPosTrx(),
                response.originalPosTrx(),
                response.originalAttemptSeq(),
                null
        );
    }

    public static PaymentReversalPrepareResult created(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        return new PaymentReversalPrepareResult(
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
