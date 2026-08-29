package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval;

import java.time.LocalDateTime;

public record VanApprovalTcpResponse(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq,
        String vanTrxId,
        VanApprovalStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime respondedAt
) {
}
