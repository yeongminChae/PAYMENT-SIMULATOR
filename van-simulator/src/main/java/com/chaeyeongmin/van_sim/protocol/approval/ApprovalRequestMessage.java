package com.chaeyeongmin.van_sim.protocol.approval;

public record ApprovalRequestMessage(
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
