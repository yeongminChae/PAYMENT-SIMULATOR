package com.chaeyeongmin.van_sim.protocol.inquiry;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * VAN 시뮬레이터가 결제 서버로 반환하는 승인 결과 조회 응답 전문 모델이다.
 * <p>
 * 이 record는 TCP JSON 응답 계약 그 자체다.
 * Payment 쪽 TcpVanGateway가 동일한 field 이름으로 역직렬화하므로,
 * protocolVersion/messageType/requestId/posTrx/attemptSeq/status 등의 이름을 변경하면 양쪽 계약이 깨진다.
 * <p>
 * status가 APPROVED이면 approvalNo와 vanTrxId가 Payment 복구에 사용된다.
 * status가 DECLINED이면 declineCode가 사용된다.
 * status가 UNKNOWN이면 Payment는 기존 UNKNOWN_TIMEOUT 상태를 유지한다.
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
        // 요청 correlation 필드는 원 요청에서 받은 값을 그대로 복사한다.
        // Payment Server는 이 값들이 어긋나면 다른 요청의 응답으로 보고 업무 DTO로 변환하지 않는다.
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
