package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval;

public record VanApprovalTcpRequest(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq,
        int amount,
        String pan,
        String expiryYyMm
) {
}
