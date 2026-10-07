package com.chaeyeongmin.payment_sim.payment.application.approval.support;

import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;
import com.chaeyeongmin.payment_sim.payment.api.common.CardSummary;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import org.springframework.stereotype.Component;

/**
 * Payment DB의 승인 상태와 저장 필드를 API 승인 응답으로 변환한다.
 *
 * <p>
 * VAN 원문이 아니라 DB에서 확인한 상태를 같은 규칙으로 응답하기 위해 사용한다.
 */
@Component
public class ApprovalResponseFactory {

    /**
     * 저장된 승인 상태에 맞춰 APPROVED/DECLINED/UNKNOWN_TIMEOUT/PROCESSING 응답을 만든다.
     */
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
