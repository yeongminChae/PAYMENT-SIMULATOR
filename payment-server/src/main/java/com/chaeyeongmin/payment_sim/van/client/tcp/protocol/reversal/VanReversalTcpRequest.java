package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal;

/**
 * Payment Server가 VAN Simulator로 보내는 TCP Reversal 요청 전문 DTO다.
 */
public record VanReversalTcpRequest(
        String protocolVersion,
        String messageType,
        String requestId,
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount
) {
}
