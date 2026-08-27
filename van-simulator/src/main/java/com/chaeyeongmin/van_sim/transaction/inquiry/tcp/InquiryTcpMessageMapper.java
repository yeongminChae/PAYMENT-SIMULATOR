package com.chaeyeongmin.van_sim.transaction.inquiry.tcp;

import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseStatus;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.InquiryResult;
import org.springframework.stereotype.Component;

/**
 * Inquiry TCP 전문과 Inquiry 서비스 결과 사이의 변환을 담당한다.
 */
@Component
public class InquiryTcpMessageMapper {

    public InquiryResponseMessage toResponse(
            InquiryRequestMessage request,
            InquiryResult result
    ) {
        InquiryResponseStatus status = switch (result.status()) {
            case APPROVED -> InquiryResponseStatus.APPROVED;
            case DECLINED -> InquiryResponseStatus.DECLINED;
            case UNKNOWN -> InquiryResponseStatus.UNKNOWN;
        };

        return InquiryResponseMessage.of(
                request.requestId(),
                request.posTrx(),
                request.attemptSeq(),
                result.vanTrxId(),
                status,
                result.approvalNo(),
                result.declineCode()
        );
    }
}
