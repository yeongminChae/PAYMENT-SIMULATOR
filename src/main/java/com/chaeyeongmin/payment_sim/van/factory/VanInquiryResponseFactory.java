 package com.chaeyeongmin.payment_sim.van.factory;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class VanInquiryResponseFactory {
    private VanInquiryResponse.VanInquiryResponseBuilder baseBuilder(
            String posTrx,
            int attemptSeq,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return VanInquiryResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .vanTrxId(vanTrxId)
                .respondedAt(respondedAt);
    }

    public VanInquiryResponse approved(
            String posTrx,
            int attemptSeq,
            String approvalNo,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, attemptSeq, vanTrxId, respondedAt)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo(approvalNo)
                .declineCode(null)
                .build();
    }

    public VanInquiryResponse declined(
            String posTrx,
            int attemptSeq,
            VanDeclineCode declineCode,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, attemptSeq, vanTrxId, respondedAt)
                .finalStatus(PaymentFinalStatus.DECLINED)
                .approvalNo(null)
                .declineCode(declineCode)
                .build();
    }

    public VanInquiryResponse unknownTimeout(
            String posTrx,
            int attemptSeq,
            VanDeclineCode declineCode,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return baseBuilder(posTrx, attemptSeq, vanTrxId, respondedAt)
                .finalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT)
                .approvalNo(null)
                .declineCode(declineCode)
                .build();
    }
    
}
