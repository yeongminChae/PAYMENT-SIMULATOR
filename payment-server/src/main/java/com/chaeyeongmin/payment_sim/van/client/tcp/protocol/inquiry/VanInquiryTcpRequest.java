package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;

/**
 * Payment Server가 VAN Simulator로 보내는 TCP Inquiry 요청 전문 DTO다.
 * <p>
 * 이 DTO는 VAN Simulator 모듈의 Java 타입을 직접 참조하지 않고,
 * Payment 모듈 안에서 동일한 JSON field 이름만 맞춘 독립 protocol 모델이다.
 * 모듈 경계를 유지하기 위해 양쪽이 같은 record를 공유하지 않는다.
 * <p>
 * Release 5 Inquiry 조회 key는 targetType + targetTrxNo + nullable targetAttemptSeq다.
 * APPROVAL은 targetAttemptSeq가 필수이고, CANCEL은 null이어야 한다.
 * <p>
 * 기존 업무 DTO인 VanInquiryRequest에는 vanTrxId/cardLast4도 있지만,
 * 실제 TCP 조회 전문에는 보내지 않는다.
 */
public record VanInquiryTcpRequest(
        String protocolVersion,
        String messageType,
        String requestId,
        VanInquiryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq
) {
}
