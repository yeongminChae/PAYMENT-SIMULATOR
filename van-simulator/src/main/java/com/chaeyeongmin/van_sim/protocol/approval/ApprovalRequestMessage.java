package com.chaeyeongmin.van_sim.protocol.approval;

/**
 * 결제 서버가 VAN 시뮬레이터로 보내는 승인 요청 전문 모델이다.
 */
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
