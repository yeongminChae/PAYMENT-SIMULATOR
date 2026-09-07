package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel;

/**
 * Payment Server가 VAN Simulator로 보내는 TCP Cancel 요청 전문 DTO다.
 *
 * <p>
 * VAN Simulator 모듈의 Java 타입을 직접 참조하지 않고, JSON field 이름만 같은 독립 protocol 모델로 둔다.
 * 모듈 경계를 유지하기 위해 양쪽이 같은 record를 공유하지 않는다.
 *
 * <p>
 * 기존 업무 DTO인 VanCancelRequest에는 cardLast4가 있지만, Cancel TCP 전문에는 보내지 않는다.
 * Payment Server가 원승인 카드 일치 검증을 끝낸 뒤, VAN에는 원승인 거래 식별 정보만 전달한다.
 */
public record VanCancelTcpRequest(
        // Cancel TCP protocol version. 현재 VAN Simulator는 "1"을 기대한다.
        String protocolVersion,
        // 요청 전문 유형. Cancel 요청은 "CANCEL"로 보낸다.
        String messageType,
        // TCP 요청/응답을 연결하는 correlation ID.
        String requestId,
        // 이번 취소 요청의 POS 거래번호. VanCancelRequest.posTrx에서 온다.
        String cancelPosTrx,
        // 취소 대상 원승인의 POS 거래번호.
        String originalPosTrx,
        // 취소 대상 원승인의 승인 시도 순번.
        int originalAttemptSeq,
        // 취소 대상 원승인의 VAN 거래번호. VanCancelRequest.vanTrxId에서 온다.
        String originalVanTrxId,
        // 취소 대상 원승인의 승인번호. VanCancelRequest.approvalNo에서 온다.
        String originalApprovalNo,
        // 취소 금액. 현재 정책에서는 원승인 금액 전체취소 금액이다.
        int amount
) {
}
