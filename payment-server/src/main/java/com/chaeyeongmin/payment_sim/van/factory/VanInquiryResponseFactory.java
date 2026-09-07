package com.chaeyeongmin.payment_sim.van.factory;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
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
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(posTrx)
                .targetAttemptSeq(attemptSeq)
                .resultCode(VanInquiryResultCode.SUCCESS)
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
                .status(VanInquiryStatus.APPROVED)
                .approvalNo(approvalNo)
                .cancelApprovalNo(null)
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
                .status(VanInquiryStatus.DECLINED)
                .approvalNo(null)
                .cancelApprovalNo(null)
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
                .status(VanInquiryStatus.UNKNOWN)
                .approvalNo(null)
                .cancelApprovalNo(null)
                .declineCode(declineCode)
                .build();
    }
    
}
