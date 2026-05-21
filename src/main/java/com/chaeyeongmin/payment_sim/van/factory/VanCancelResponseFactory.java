package com.chaeyeongmin.payment_sim.van.factory;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class VanCancelResponseFactory {

    private VanCancelResponse.VanCancelResponseBuilder baseBuilder(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return VanCancelResponse.builder()
                .posTrx(posTrx)
                .originalPosTrx(originalPosTrx)
                .originalAttemptSeq(originalAttemptSeq)
                .vanTrxId(vanTrxId)
                .respondedAt(respondedAt)
                ;
    }

    public VanCancelResponse cancelled(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String vanTrxId,
            String approvalNo,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, originalPosTrx, originalAttemptSeq, vanTrxId, respondedAt)
                .cancelStatus(CancelStatus.CANCELLED)
                .cancelApprovalNo(approvalNo)
                .declineCode(null)
                .build();
    }

    public VanCancelResponse declined(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String vanTrxId,
            VanDeclineCode declineCode,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, originalPosTrx, originalAttemptSeq, vanTrxId, respondedAt)
                .cancelStatus(CancelStatus.CANCEL_DECLINED)
                .cancelApprovalNo(null)
                .declineCode(declineCode)
                .build();
    }

    public VanCancelResponse unknownTimeout(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String vanTrxId,
            VanDeclineCode declineCode,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, originalPosTrx, originalAttemptSeq, vanTrxId, respondedAt)
                .cancelStatus(CancelStatus.PENDING)
                .cancelApprovalNo(null)
                .declineCode(declineCode)
                .build();
    }

}
