package com.chaeyeongmin.van_sim.transaction.inquiry.tcp;

import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.InquiryService;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.InquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.tcp.exception.InquiryTcpMessageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Inquiry TCP 요청 전문을 Inquiry 서비스에 연결하는 진입 핸들러다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
public class InquiryTcpHandler {

    private static final String PROTOCOL_VERSION = "1";
    private static final String MESSAGE_TYPE = "INQUIRY";

    private final ObjectMapper objectMapper;
    private final InquiryTcpMessageMapper tcpMessageMapper;
    private final InquiryService inquiryService;

    public byte[] handle(byte[] payload) {
        InquiryRequestMessage request = readInquiryRequest(payload);
        validate(request);

        InquiryResult result = inquiryService.inquire(
                request.posTrx(),
                request.attemptSeq()
        );
        InquiryResponseMessage response = tcpMessageMapper.toResponse(request, result);

        return writeInquiryResponse(response);
    }

    private InquiryRequestMessage readInquiryRequest(byte[] payload) {
        try {
            return objectMapper.readValue(payload, InquiryRequestMessage.class);
        } catch (IOException e) {
            throw new InquiryTcpMessageException(
                    "INQUIRY_TCP_REQUEST_DESERIALIZE_FAILED",
                    e
            );
        }
    }

    private void validate(InquiryRequestMessage request) {
        if (!PROTOCOL_VERSION.equals(request.protocolVersion())
                || !MESSAGE_TYPE.equals(request.messageType())
                || isBlank(request.requestId())
                || isBlank(request.posTrx())
                || request.attemptSeq() <= 0) {
            throw new InquiryTcpMessageException("INQUIRY_TCP_REQUEST_INVALID");
        }
    }

    private byte[] writeInquiryResponse(InquiryResponseMessage response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (IOException e) {
            throw new InquiryTcpMessageException(
                    "INQUIRY_TCP_RESPONSE_SERIALIZE_FAILED",
                    e
            );
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
