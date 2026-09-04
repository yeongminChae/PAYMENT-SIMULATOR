package com.chaeyeongmin.payment_sim.api.payment.dto.response;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.ReversalResultStatus;

/**
 * [API Response] 결제 reversal 응답 DTO.
 */
public record ReversalResponse(
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        ReversalResultStatus reversalStatus,
        String reversalApprovalNo,
        String declineCode
) {

    public static ReversalResponse reversed(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String reversalApprovalNo
    ) {
        return new ReversalResponse(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                ReversalResultStatus.REVERSED,
                reversalApprovalNo,
                null
        );
    }

    public static ReversalResponse alreadyReversed(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String reversalApprovalNo
    ) {
        return new ReversalResponse(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                ReversalResultStatus.ALREADY_REVERSED,
                reversalApprovalNo,
                null
        );
    }

    public static ReversalResponse declined(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String declineCode
    ) {
        return new ReversalResponse(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                ReversalResultStatus.REVERSAL_DECLINED,
                null,
                declineCode
        );
    }

    public static ReversalResponse reversalNotAllowed(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String declineCode
    ) {
        return new ReversalResponse(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                ReversalResultStatus.REVERSAL_NOT_ALLOWED,
                null,
                declineCode
        );
    }

    public static ReversalResponse retryLater(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        return new ReversalResponse(
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                ReversalResultStatus.RETRY_LATER,
                null,
                null
        );
    }
}
