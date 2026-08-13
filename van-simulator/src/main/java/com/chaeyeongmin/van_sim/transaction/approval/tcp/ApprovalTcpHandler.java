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
        ApprovalRequestMessage approvalRequest = readApprovalRequest(payload);

        ApprovalCommand approvalCommand = tcpMessageMapper.toCommand(approvalRequest);

        ApprovalResult approvalResult = approvalService.processApproval(approvalCommand);

        ApprovalResponseMessage approvalResponse =
                tcpMessageMapper.toResponse(approvalRequest, approvalResult);

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
