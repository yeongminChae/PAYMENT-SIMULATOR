package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal;

/**
 * VAN Simulator가 반환하는 TCP Reversal 응답 전문 DTO다.
 */
public record VanReversalTcpResponse(
        String protocolVersion,
        String messageType,
        String requestId,
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        String vanReversalTrxId,
        VanReversalTcpStatus reversalStatus,
        VanReversalTcpResultCode resultCode,
        String reversalApprovalNo,
        String declineCode
) {
}
