package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel;

/**
 * VAN Simulator가 반환하는 TCP Cancel 응답 전문 DTO다.
 *
 * <p>
 * ObjectMapper가 VAN의 JSON payload를 이 record로 바로 역직렬화한다.
 * field 이름은 VAN Simulator의 CANCEL_RESPONSE JSON 계약과 정확히 일치해야 한다.
 *
 * <p>
 * 이 DTO는 아직 Payment 업무 DTO가 아니다.
 * TcpVanGateway가 validation을 통과한 뒤 VanCancelResponse로 변환하면서
 * VAN cancelStatus/resultCode/declineCode를 Payment 계층 의미로 매핑한다.
 */
public record VanCancelTcpResponse(
        // VAN Simulator가 응답한 protocol version.
        String protocolVersion,
        // 응답 전문 유형. 정상 Cancel 응답은 "CANCEL_RESPONSE"여야 한다.
        String messageType,
        // 요청에서 보낸 requestId가 그대로 돌아와야 한다.
        String requestId,
        // 현재 요청 기준 취소 POS 거래번호.
        String cancelPosTrx,
        // 취소 대상 원승인의 POS 거래번호.
        String originalPosTrx,
        // 취소 대상 원승인의 승인 시도 순번.
        int originalAttemptSeq,
        // VAN이 생성하거나 기존 원장에서 재사용한 취소 거래번호.
        String vanCancelTrxId,
        // VAN 취소 원장 상태.
        VanCancelTcpStatus cancelStatus,
        // 현재 취소 요청 관점의 결과 코드.
        VanCancelTcpResultCode resultCode,
        // 정상 취소 성공 시 VAN이 발급한 취소 승인번호.
        String cancelApprovalNo,
        // 취소 거절 시 VAN이 내려준 거절 코드.
        String declineCode
) {
}
