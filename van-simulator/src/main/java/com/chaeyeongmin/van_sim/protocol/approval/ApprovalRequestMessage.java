package com.chaeyeongmin.van_sim.protocol.approval;

/**
 * 결제 서버가 VAN 시뮬레이터로 보내는 승인 요청 전문 모델이다.
 * <p>
 * TCP로 들어온 JSON payload의 구조를 그대로 표현하는 프로토콜 계층 객체라서,
 * 서비스 처리에 직접 필요하지 않은 전문 식별 정보도 함께 보관한다.
 *
 * @param protocolVersion 승인 전문 프로토콜 버전
 * @param messageType 전문 유형
 * @param requestId 요청과 응답을 연결하기 위한 결제 서버의 요청 식별자
 * @param posTrx POS 거래 식별자
 * @param attemptSeq 같은 거래의 승인 시도 순번
 * @param amount 승인 요청 금액
 * @param pan 카드 번호 원문
 * @param expiryYyMm 카드 유효기간
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
