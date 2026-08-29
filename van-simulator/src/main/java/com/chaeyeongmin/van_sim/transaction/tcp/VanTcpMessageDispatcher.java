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
 * <p>
 * VAN TCP 서버는 하나의 port로 APPROVAL과 INQUIRY를 모두 받는다.
 * 그래서 transport 설정에서 업무 핸들러를 직접 고르지 않고,
 * 이 dispatcher가 JSON의 messageType을 먼저 읽어 올바른 핸들러로 라우팅한다.
 * <p>
 * 이 클래스는 messageType 판별만 담당한다.
 * 승인/조회 전문 전체 validation은 각 업무 핸들러가 수행한다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
public class VanTcpMessageDispatcher {

    private final ObjectMapper objectMapper;
    private final ApprovalTcpHandler approvalTcpHandler;
    private final InquiryTcpHandler inquiryTcpHandler;

    /**
     * 수신 TCP payload를 messageType 기준으로 업무 핸들러에 위임한다.
     * <p>
     * payload는 length header가 제거된 JSON byte[]다.
     * APPROVAL은 승인 원장 생성/응답 흐름으로,
     * INQUIRY는 기존 승인 원장 조회 흐름으로 보낸다.
     */
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

    /**
     * 전체 전문을 업무 DTO로 역직렬화하기 전에 messageType만 빠르게 읽는다.
     * <p>
     * JsonNode로 먼저 파싱하는 이유는 APPROVAL/INQUIRY가 서로 다른 DTO를 사용하기 때문이다.
     * messageType이 없거나 문자열이 아니면 어느 업무 핸들러로 보낼 수 없으므로 dispatcher 단계에서 중단한다.
     */
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
