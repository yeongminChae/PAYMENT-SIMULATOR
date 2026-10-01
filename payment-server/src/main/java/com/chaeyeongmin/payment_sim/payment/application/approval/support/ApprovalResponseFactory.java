package com.chaeyeongmin.payment_sim.payment.application.approval.support;

import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;
import com.chaeyeongmin.payment_sim.payment.api.common.CardSummary;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import org.springframework.stereotype.Component;

@Component
public class ApprovalResponseFactory {

    public ApproveResponse fromStatus(
            PaymentFinalStatus status,
            String posTrx,
            int attemptSeq,
            String approvalNo,
            String declineCode,
            CardSummary cardSummary
    ) {
        return switch (status) {
            case APPROVED -> ApproveResponse.approved(posTrx, attemptSeq, approvalNo, cardSummary);
            case DECLINED -> ApproveResponse.declined(posTrx, attemptSeq, declineCode, cardSummary);
            case UNKNOWN_TIMEOUT -> ApproveResponse.unknownTimeout(posTrx, attemptSeq, declineCode, cardSummary);
            case PROCESSING -> ApproveResponse.retryLater(posTrx, attemptSeq, cardSummary);
        };
    }

}
