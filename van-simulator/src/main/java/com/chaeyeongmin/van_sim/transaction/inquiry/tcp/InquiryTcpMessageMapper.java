package com.chaeyeongmin.van_sim.transaction.inquiry.tcp;

import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseStatus;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.InquiryResult;
import org.springframework.stereotype.Component;

/**
 * Inquiry TCP 전문과 Inquiry 서비스 결과 사이의 변환을 담당한다.
 * <p>
 * TCP handler가 JSON 처리와 validation을 맡고,
 * InquiryService가 DB 조회를 맡는다면,
 * 이 mapper는 그 둘 사이에서 protocol DTO와 service result의 모양을 맞추는 얇은 변환 계층이다.
 * 여기서 DB 조회, 시나리오 판단, transport 동작 같은 부수 효과를 만들지 않는다.
 */
@Component
public class InquiryTcpMessageMapper {

    /**
     * Inquiry 서비스 결과를 TCP 응답 전문으로 변환한다.
     * <p>
     * requestId, posTrx, attemptSeq는 요청에서 온 correlation 값이므로 request에서 가져오고,
     * vanTrxId, approvalNo, declineCode, status는 VAN 원장 조회 결과에서 가져온다.
     * 이렇게 분리해야 Payment가 "내가 보낸 조회 요청에 대한 응답인지" 검증하면서도
     * VAN 원장의 실제 승인 결과를 받을 수 있다.
     */
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
