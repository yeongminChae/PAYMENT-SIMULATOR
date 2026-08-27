package com.chaeyeongmin.van_sim.transaction.tcp;

import com.chaeyeongmin.van_sim.transaction.approval.tcp.ApprovalTcpHandler;
import com.chaeyeongmin.van_sim.transaction.inquiry.tcp.InquiryTcpHandler;
import com.chaeyeongmin.van_sim.transaction.tcp.exception.VanTcpMessageException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * TCP JSON payload의 messageType만 확인해 업무별 TCP 핸들러로 위임한다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
public class VanTcpMessageDispatcher {

    private final ObjectMapper objectMapper;
    private final ApprovalTcpHandler approvalTcpHandler;
    private final InquiryTcpHandler inquiryTcpHandler;

    public byte[] dispatch(byte[] payload) {
        String messageType = readMessageType(payload);

        return switch (messageType) {
            case "APPROVAL" -> approvalTcpHandler.handle(payload);
            case "INQUIRY" -> inquiryTcpHandler.handle(payload);
            default -> throw new VanTcpMessageException(
                    "VAN_TCP_MESSAGE_TYPE_UNSUPPORTED: " + messageType
            );
        };
    }

    private String readMessageType(byte[] payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode messageType = root.get("messageType");

            if (messageType == null || !messageType.isTextual() || messageType.asText().isBlank()) {
                throw new VanTcpMessageException("VAN_TCP_MESSAGE_TYPE_MISSING");
            }

            return messageType.asText();
        } catch (IOException e) {
            throw new VanTcpMessageException("VAN_TCP_REQUEST_DESERIALIZE_FAILED", e);
        }
    }
}
