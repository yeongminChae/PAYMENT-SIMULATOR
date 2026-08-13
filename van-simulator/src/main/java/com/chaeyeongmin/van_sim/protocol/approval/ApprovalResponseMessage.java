package com.chaeyeongmin.van_sim.protocol.approval;

import java.time.LocalDateTime;

/**
 * VAN 시뮬레이터가 결제 서버로 반환하는 승인 응답 전문 모델이다.
 */
public record ApprovalResponseMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq,
        String vanTrxId,
        ApprovalStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime respondedAt
) {
}
