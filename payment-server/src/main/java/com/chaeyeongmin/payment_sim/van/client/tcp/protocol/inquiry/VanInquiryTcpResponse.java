package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry;

import java.time.LocalDateTime;

public record VanInquiryTcpResponse(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq,
        String vanTrxId,
        VanInquiryStatus status,
        String approvalNo,
        String declineCode,
        LocalDateTime respondedAt
) {
}
