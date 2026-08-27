package com.chaeyeongmin.van_sim.protocol.inquiry;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * VAN 시뮬레이터가 결제 서버로 반환하는 승인 결과 조회 응답 전문 모델이다.
 */
@Builder
public record InquiryResponseMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq,
        String vanTrxId,
        InquiryResponseStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime respondedAt
) {

    public static InquiryResponseMessage of(
            String requestId,
            String posTrx,
            int attemptSeq,
            String vanTrxId,
            InquiryResponseStatus status,
            String approvalNo,
            String declineCode
    ) {
        return InquiryResponseMessage.builder()
                .protocolVersion("1")
                .messageType("INQUIRY_RESPONSE")
                .requestId(requestId)
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .vanTrxId(vanTrxId)
                .status(status)
                .approvalNo(approvalNo)
                .declineCode(declineCode)
                .respondedAt(LocalDateTime.now())
                .build();
    }
}
