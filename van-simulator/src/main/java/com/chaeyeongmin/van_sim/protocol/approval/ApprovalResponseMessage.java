package com.chaeyeongmin.van_sim.protocol.approval;

import lombok.Builder;

import java.time.LocalDateTime;

/**
 * VAN 시뮬레이터가 결제 서버로 반환하는 승인 응답 전문 모델이다.
 */
@Builder
public record ApprovalResponseMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq,
        String vanTrxId,
        ApprovalResponseStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime respondedAt
) {

    public static ApprovalResponseMessage of(
            String requestId,
            String posTrx,
            int attemptSeq,
            String vanTrxId,
            ApprovalResponseStatus status,
            String approvalNo,
            String declineCode
    ) {
        return ApprovalResponseMessage.builder()
                .protocolVersion("1.0") // 공통 규격 값 세팅
                .messageType("APPROVAL")
                .requestId(requestId)
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .vanTrxId(vanTrxId)
                .status(status)
                .approvalNo(approvalNo)
                .declineCode(declineCode)
                .respondedAt(LocalDateTime.now()) // 생성 시점 주입
                .build();
    }

}
