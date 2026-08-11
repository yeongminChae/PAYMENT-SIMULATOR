package com.chaeyeongmin.van_sim.protocol.approval;

import java.time.LocalDateTime;

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
