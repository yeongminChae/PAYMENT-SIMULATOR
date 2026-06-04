package com.chaeyeongmin.payment_sim.van.factory;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class VanInquiryResponseFactory {
    // VAN 계층 DTO에서는 VanDeclineCode enum을 유지한다.
    // DB 저장/API 응답용 String code 변환은 서비스 계층에서 declineCode.code()로 처리한다.
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
