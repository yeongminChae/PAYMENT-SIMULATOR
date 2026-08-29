package com.chaeyeongmin.van_sim.protocol.inquiry;

/**
 * 결제 서버가 VAN 시뮬레이터로 보내는 승인 결과 조회 요청 전문 모델이다.
 * <p>
 * 실제 TCP payload의 JSON 필드 이름과 1:1로 맞춰야 하므로 field rename에 특히 주의해야 한다.
 * Inquiry는 재승인이 아니기 때문에 카드번호, 금액, expiry 같은 승인 입력값을 받지 않는다.
 * 조회 키는 posTrx와 attemptSeq뿐이며, requestId는 응답 correlation 검증용으로 그대로 돌려준다.
 */
public record InquiryRequestMessage(
        String protocolVersion,
        String messageType,
        String requestId,
        String posTrx,
        int attemptSeq
) {
}
