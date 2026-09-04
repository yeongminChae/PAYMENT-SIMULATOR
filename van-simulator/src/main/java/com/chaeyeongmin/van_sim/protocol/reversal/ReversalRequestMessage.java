package com.chaeyeongmin.van_sim.protocol.reversal;

/**
 * 결제 서버가 VAN 시뮬레이터로 보내는 reversal 요청 전문 모델이다.
 *
 * <p>
 * TCP JSON payload의 필드 이름과 1:1로 맞는 protocol 계층 객체다.
 * Reversal은 장애 복구 거래이므로 originalApprovalNo/originalVanTrxId를 받지 않는다.
 */
public record ReversalRequestMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        String reversalPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        int amount
) {
}
