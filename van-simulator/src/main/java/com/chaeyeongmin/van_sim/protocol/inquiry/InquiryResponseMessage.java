package com.chaeyeongmin.van_sim.protocol.inquiry;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * VAN 시뮬레이터가 결제 서버로 반환하는 공용 Inquiry 응답 전문 모델이다.
 * <p>
 * 이 record는 TCP JSON 응답 계약 그 자체다.
 * Payment 쪽 TcpVanGateway가 동일한 field 이름으로 역직렬화하므로,
 * protocolVersion/messageType/requestId/targetTrxNo/targetAttemptSeq/status 등의 이름을 변경하면 양쪽 계약이 깨진다.
 * <p>
 * vanTrxId에는 조회 대상 원장의 VAN 추적 ID가 들어간다.
 * status가 APPROVED이면 approvalNo가 Payment 복구에 사용된다.
 * status가 CANCELLED이면 cancelApprovalNo, REVERSED이면 reversalApprovalNo를 사용한다.
 * DECLINED, CANCEL_DECLINED, REVERSAL_DECLINED이면 declineCode를 사용한다.
 * status가 UNKNOWN이면 Payment는 기존 UNKNOWN_TIMEOUT 상태를 유지한다.
 */
@Builder
public record InquiryResponseMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        InquiryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        InquiryResultCode resultCode,
        String vanTrxId,
        InquiryResponseStatus status,
        String approvalNo,
        String cancelApprovalNo,
        String reversalApprovalNo,
        String declineCode,
        LocalDateTime respondedAt
) {

    /** 요청의 correlation 값과 VAN 원장 조회 결과를 결합해 공용 Inquiry 응답 전문을 만든다. */
    public static InquiryResponseMessage of(
            String requestId,
            InquiryTargetType targetType,
            String targetTrxNo,
            Integer targetAttemptSeq,
            InquiryResultCode resultCode,
            String vanTrxId,
            InquiryResponseStatus status,
            String approvalNo,
            String cancelApprovalNo,
            String reversalApprovalNo,
            String declineCode
    ) {
        // 요청 correlation 필드는 원 요청에서 받은 값을 그대로 복사한다.
        // Payment Server는 이 값들이 어긋나면 다른 요청의 응답으로 보고 업무 DTO로 변환하지 않는다.
        return InquiryResponseMessage.builder()
                .protocolVersion("1")
                .messageType("INQUIRY_RESPONSE")
                .requestId(requestId)
                .targetType(targetType)
                .targetTrxNo(targetTrxNo)
                .targetAttemptSeq(targetAttemptSeq)
                .resultCode(resultCode)
                .vanTrxId(vanTrxId)
                .status(status)
                .approvalNo(approvalNo)
                .cancelApprovalNo(cancelApprovalNo)
                .reversalApprovalNo(reversalApprovalNo)
                .declineCode(declineCode)
                .respondedAt(LocalDateTime.now())
                .build();
    }

    public String posTrx() {
        return targetTrxNo;
    }

    public int attemptSeq() {
        return targetAttemptSeq == null ? 0 : targetAttemptSeq;
    }
}
