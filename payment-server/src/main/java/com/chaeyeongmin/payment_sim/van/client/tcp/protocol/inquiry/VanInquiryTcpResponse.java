package com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;

import java.time.LocalDateTime;

/**
 * VAN Simulator가 반환하는 TCP Inquiry 응답 전문 DTO다.
 * <p>
 * ObjectMapper가 VAN의 JSON payload를 이 record로 바로 역직렬화한다.
 * field 이름은 VAN Simulator의 INQUIRY_RESPONSE JSON 계약과 정확히 일치해야 한다.
 * <p>
 * 이 DTO는 아직 Payment 업무 DTO가 아니다.
 * TcpVanGateway가 correlation/semantic validation을 통과한 뒤 VanInquiryResponse로 변환한다.
 * APPROVAL status와 CANCEL status가 섞이면 여기서 업무 계층으로 넘기지 않아야 한다.
 */
public record VanInquiryTcpResponse(
        String protocolVersion,
        String messageType,
        String requestId,
        VanInquiryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        VanInquiryResultCode resultCode,
        String vanTrxId,
        VanInquiryStatus status,
        String approvalNo,
        String cancelApprovalNo,
        String declineCode,
        LocalDateTime respondedAt
) {
}
