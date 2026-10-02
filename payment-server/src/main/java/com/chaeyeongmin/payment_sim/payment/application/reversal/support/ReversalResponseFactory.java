package com.chaeyeongmin.payment_sim.payment.application.reversal.support;

import com.chaeyeongmin.payment_sim.payment.api.reversal.ReversalResponse;
import com.chaeyeongmin.payment_sim.payment.domain.reversal.PaymentReversal;
import org.springframework.stereotype.Component;

@Component
public class ReversalResponseFactory {

    /**
     * 같은 reversalPosTrx 재요청에 대해 현재 PAYMENT_REVERSAL row 기준 응답을 만든다.
     */
    public ReversalResponse fromExistingCurrent(PaymentReversal reversal) {
        return switch (reversal.reversalStatus()) {
            case PENDING -> ReversalResponse.retryLater(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq()
            );
            case REVERSED -> ReversalResponse.reversed(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResponse.declined(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.declineCode()
            );
        };
    }

    /**
     * 같은 원승인에 이미 reversal row가 있을 때 현재 요청의 reversalPosTrx로 응답을 만든다.
     *
     * <p>
     * 기존 row가 REVERSED이면 현재 요청은 신규 성공이 아니라 이미 reversal된 원거래에 대한 재요청이므로
     * ALREADY_REVERSED로 응답한다.
     */
    public ReversalResponse fromExistingOriginal(String currentReversalPosTrx, PaymentReversal reversal) {
        return switch (reversal.reversalStatus()) {
            case PENDING -> ReversalResponse.retryLater(
                    currentReversalPosTrx,
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq()
            );
            case REVERSED -> ReversalResponse.alreadyReversed(
                    currentReversalPosTrx,
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResponse.declined(
                    currentReversalPosTrx,
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.declineCode()
            );
        };
    }

    /**
     * finalize 이후 현재 reversal row 기준 응답을 만든다.
     *
     * <p>
     * 이 경로는 현재 요청이 VAN reversal을 호출한 뒤의 응답이므로 REVERSED를 ALREADY_REVERSED로 바꾸지 않는다.
     */
    public ReversalResponse fromFinalizedCurrent(PaymentReversal reversal) {
        return switch (reversal.reversalStatus()) {
            case PENDING -> ReversalResponse.retryLater(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq()
            );
            case REVERSED -> ReversalResponse.reversed(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResponse.declined(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.declineCode()
            );
        };
    }
}
