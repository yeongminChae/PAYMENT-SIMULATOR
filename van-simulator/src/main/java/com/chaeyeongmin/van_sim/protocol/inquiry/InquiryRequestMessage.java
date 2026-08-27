package com.chaeyeongmin.van_sim.protocol.inquiry;

/**
 * 결제 서버가 VAN 시뮬레이터로 보내는 승인 결과 조회 요청 전문 모델이다.
 */
public record InquiryRequestMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq
) {
}
