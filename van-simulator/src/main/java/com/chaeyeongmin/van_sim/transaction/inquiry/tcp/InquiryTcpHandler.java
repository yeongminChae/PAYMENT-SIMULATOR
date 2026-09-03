package com.chaeyeongmin.van_sim.transaction.inquiry.tcp;

import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.InquiryService;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.CancelInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.tcp.exception.InquiryTcpMessageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

/**
 * Inquiry TCP 요청 전문을 Inquiry 서비스에 연결하는 진입 핸들러다.
 * <p>
 * 이 클래스의 책임은 transport payload와 Inquiry 업무 서비스를 이어주는 것이다.
 * 처리 순서는 항상 "역직렬화 -> 프로토콜 validation -> 원장 조회 -> 응답 전문 생성 -> 직렬화"다.
 * ApprovalTcpHandler와 달리 DROP_RESPONSE 같은 transport scenario를 적용하지 않는다.
 * Inquiry는 Payment가 이미 잃어버린 승인 결과를 복구하기 위한 조회이므로,
 * 정상적으로 조회 응답을 돌려주는 것이 목적이다.
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

    /**
     * TCP 서버가 넘긴 JSON payload를 받아 Inquiry 응답 JSON payload를 반환한다.
     * <p>
     * 여기서 payload는 이미 4-byte length header가 제거된 순수 JSON byte[]다.
     * length framing은 Spring Integration 설정이 처리하고,
     * 이 메서드는 message body의 업무 의미만 다룬다.
     */
    public byte[] handle(byte[] payload) {
        InquiryRequestMessage request = readInquiryRequest(payload);

        validate(request);

        InquiryResponseMessage response =
                switch (request.targetType()) {
                    // 조회 키는 posTrx + attemptSeq다.
                    // 카드 last4나 기존 vanTrxId 없이도 VAN 원장의 unique key로 정확한 승인 시도를 찾을 수 있다.
                    case APPROVAL -> handleApprovalInquiry(request);
                    case CANCEL -> handleCancelInquiry(request);
                };

        return writeInquiryResponse(response);
    }

    private InquiryResponseMessage handleApprovalInquiry(InquiryRequestMessage request) {
        Optional<ApprovalInquiryResult> result =
                inquiryService.inquireApproval(
                        request.targetTrxNo(),
                        request.targetAttemptSeq()
                );

        return result.isPresent()
                ? tcpMessageMapper.toApprovalResponse(request, result.get())
                : tcpMessageMapper.notFoundResponse(request)
            ;

    }

    private InquiryResponseMessage handleCancelInquiry(InquiryRequestMessage request) {
        Optional<CancelInquiryResult> result = inquiryService.inquireCancel(request.targetTrxNo());

        return result.isPresent()
                ? tcpMessageMapper.toCancelResponse(request, result.get())
                : tcpMessageMapper.notFoundResponse(request)
        ;

    }

    /**
     * TCP JSON payload를 Inquiry 요청 전문 DTO로 읽는다.
     * <p>
     * JSON 자체가 깨졌거나 필드 타입이 맞지 않으면 업무 서비스까지 내려가지 않고
     * TCP protocol 계층 예외로 중단한다.
     */
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

    /**
     * Inquiry 요청 전문의 최소 계약을 검증한다.
     * <p>
     * protocolVersion/messageType은 이 핸들러가 처리할 수 있는 전문인지 확인하는 값이고,
     * requestId는 Payment의 응답 correlation 검증에 필요하다.
     * posTrx/attemptSeq는 VAN 원장 조회 key이므로 비어 있거나 0 이하이면 조회 자체가 성립하지 않는다.
     */
    private void validate(InquiryRequestMessage request) {
        if (PROTOCOL_VERSION.equals(request.protocolVersion()) == false
                || MESSAGE_TYPE.equals(request.messageType()) == false
                || isBlank(request.requestId())
                || request.targetType() == null
                || isBlank(request.targetTrxNo())
                || isInvalidTargetAttemptSeq(request)) {
            throw new InquiryTcpMessageException("INQUIRY_TCP_REQUEST_INVALID");
        }
    }

    private boolean isInvalidTargetAttemptSeq(InquiryRequestMessage request) {
        return switch (request.targetType()) {
            case APPROVAL -> request.targetAttemptSeq() == null || request.targetAttemptSeq() <= 0;
            case CANCEL -> request.targetAttemptSeq() != null;
        };
    }

    /**
     * Inquiry 응답 전문 DTO를 TCP 응답 payload로 직렬화한다.
     * <p>
     * 여기서 만드는 byte[]도 length header가 없는 JSON body다.
     * 실제 length-prefixed 응답 프레임은 Spring Integration TCP gateway가 붙인다.
     */
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

    /**
     * null, 빈 문자열, 공백 문자열을 같은 invalid 값으로 보기 위한 작은 검증 헬퍼다.
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
