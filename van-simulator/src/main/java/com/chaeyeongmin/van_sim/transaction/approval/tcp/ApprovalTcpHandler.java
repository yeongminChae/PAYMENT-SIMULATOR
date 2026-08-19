package com.chaeyeongmin.van_sim.transaction.approval.tcp;

import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.transaction.approval.service.ApprovalService;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.approval.tcp.exception.ApprovalTcpMessageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 승인 TCP 요청 전문을 승인 서비스에 연결하는 진입 핸들러다.
 * <p>
 * 수신한 JSON 바이트를 승인 요청 전문으로 역직렬화하고, 서비스 처리 결과를 다시 승인 응답 전문 바이트로 직렬화한다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
public class ApprovalTcpHandler {

    private final ObjectMapper objectMapper;
    private final ApprovalTcpMessageMapper tcpMessageMapper;
    private final ApprovalService approvalService;

    /**
     * 승인 TCP 요청 payload를 처리하고 응답 payload를 반환한다.
     */
    public byte[] handle(byte[] payload) {
        // TCP 서버가 수신한 원본 JSON 바이트 payload를 승인 요청 전문 객체로 역직렬화한다.
        ApprovalRequestMessage approvalRequest = readApprovalRequest(payload);

        // 승인 요청 전문에 담긴 거래 정보를 서비스 계층이 처리할 수 있는 커맨드 모델로 변환한다.
        ApprovalCommand approvalCommand = tcpMessageMapper.toCommand(approvalRequest);

        // 승인 서비스에 커맨드를 전달해 카드 승인 가능 여부와 응답에 필요한 처리 결과를 계산한다.
        ApprovalResult approvalResult = approvalService.processApproval(approvalCommand);

        // 원 요청 전문의 식별 정보와 서비스 처리 결과를 조합해 TCP 응답 전문 객체를 만든다.
        ApprovalResponseMessage approvalResponse =
                tcpMessageMapper.toResponse(approvalRequest, approvalResult);

        // 응답 전문 객체를 TCP 클라이언트로 되돌려 보낼 JSON 바이트 payload로 직렬화한다.
        return writeApprovalResponse(approvalResponse);
    }

    /**
     * 수신 payload를 승인 요청 전문으로 변환한다.
     */
    private ApprovalRequestMessage readApprovalRequest(byte[] payload) {
        try {
            return objectMapper.readValue(payload, ApprovalRequestMessage.class);
        } catch (IOException e) {
            throw new ApprovalTcpMessageException(
                    "APPROVAL_TCP_REQUEST_DESERIALIZE_FAILED",
                    e
            );
        }

    }

    /**
     * 승인 응답 전문을 송신 payload로 변환한다.
     */
    private byte[] writeApprovalResponse(ApprovalResponseMessage response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (IOException e) {
            throw new ApprovalTcpMessageException(
                    "APPROVAL_TCP_RESPONSE_SERIALIZE_FAILED",
                    e
            );
        }

    }

}
