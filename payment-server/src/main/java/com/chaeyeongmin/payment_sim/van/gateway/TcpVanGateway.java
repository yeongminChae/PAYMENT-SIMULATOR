package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.*;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpResponse;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 기존 VanGateway 경계를 유지하면서 Payment 업무 DTO를 실제 TCP VAN Simulator 호출로 바꾸는 어댑터다.
 * <p>
 * 책임:
 * - 기존 업무 DTO({@link VanApproveRequest}, {@link VanApproveResponse}, {@link VanInquiryRequest}, {@link VanInquiryResponse})와 TCP 전문 DTO 사이를 변환한다.
 * - TCP 전문 DTO를 JSON byte[]로 직렬화/역직렬화한다.
 * - 응답 correlation 검증을 수행해 다른 요청의 응답을 업무 결과로 사용하지 않도록 막는다.
 * - TCP 응답 대기 timeout을 Payment 계층의 {@link VanGatewayTimeoutException}으로 변환한다.
 * - byte[] 송수신은 {@link VanTcpClient}에 위임한다.
 * <p>
 * PaymentService는 이 구현체를 직접 알지 않고 VanGateway만 의존한다.
 * 즉, TCP 계약 변경은 최대한 이 boundary 안에서 흡수하고 업무 서비스의 흐름은 유지한다.
 */
@Component
@ConditionalOnProperty(name = "payment.van.mode", havingValue = "tcp")
@RequiredArgsConstructor
public class TcpVanGateway implements VanGateway {

    private static final String PROTOCOL_VERSION = "1";
    private static final String APPROVAL_MESSAGE_TYPE = "APPROVAL";
    private static final String APPROVAL_RESPONSE_MESSAGE_TYPE = "APPROVAL_RESPONSE";
    private static final String INQUIRY_MESSAGE_TYPE = "INQUIRY";
    private static final String INQUIRY_RESPONSE_MESSAGE_TYPE = "INQUIRY_RESPONSE";

    private final ObjectMapper objectMapper;
    private final VanTcpClient vanTcpClient;

    /**
     * [A6] 기존 Payment 승인 요청을 TCP VAN Simulator 승인 호출로 변환한다.
     * <p>
     * 흐름:
     * VanApproveRequest -> VanApprovalTcpRequest -> JSON byte[] -> VanTcpClient -> JSON byte[] 응답
     * -> VanApprovalTcpResponse -> VanApproveResponse
     */
    @Override
    public VanApproveResponse approve(VanApproveRequest request) {
        try {
            VanApprovalTcpRequest tcpRequest = toTcpRequest(request);
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanApprovalTcpResponse tcpResponse = readResponse(responsePayload);

            validateResponse(tcpRequest, tcpResponse);
            return toApproveResponse(request, tcpResponse);

        } catch (VanTcpRequestNotSentException e) {
            throw new VanGatewayRequestNotSentException(e);
        } catch (VanTcpResponseTimeoutException e) {
            throw new VanGatewayTimeoutException(e);
        }

    }

    @Override
    public VanCancelResponse cancel(VanCancelRequest request) {
        // 이번 단계는 승인 TCP 정상 흐름만 연결한다. 취소는 기존 범위 밖이므로 명시적으로 막아둔다.
        throw new UnsupportedOperationException("TCP VAN cancel is not implemented yet");
    }

    @Override
    public VanInquiryResponse inquiry(VanInquiryRequest request) {
        try {
            // Payment의 업무 조회 요청을 VAN TCP 계약에 맞는 INQUIRY JSON 전문으로 바꾼다.
            // 이때 조회 key는 posTrx/attemptSeq뿐이며, 업무 DTO의 cardLast4/vanTrxId는 보내지 않는다.
            VanInquiryTcpRequest tcpRequest = toTcpRequest(request);

            // 아래 흐름은 approve()와 같은 구조다.
            // Gateway는 JSON 변환과 protocol 검증을 맡고, 실제 socket 송수신은 VanTcpClient에 맡긴다.
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanInquiryTcpResponse tcpResponse = readInquiryResponse(responsePayload);

            // VAN 응답이 내가 보낸 Inquiry 요청과 같은 거래를 가리키는지 확인한 뒤에만 업무 DTO로 변환한다.
            validateInquiryResponse(tcpRequest, tcpResponse);
            return toInquiryResponse(tcpResponse);

        } catch (VanTcpRequestNotSentException e) {
            throw new VanGatewayRequestNotSentException(e);
        } catch (VanTcpResponseTimeoutException e) {
            // transport 계층의 read timeout 예외를 업무 gateway boundary 예외로 감싼다.
            // PaymentInquiryServiceImpl은 별도 timeout 복구 정책을 만들지 않았으므로 상위 예외 처리로 전파된다.
            throw new VanGatewayTimeoutException(e);
        }
    }

    /**
     * 기존 업무 승인 요청 DTO를 VAN TCP 승인 요청 전문 DTO로 변환한다.
     * <p>
     * protocolVersion/messageType은 VAN Simulator v1 승인 프로토콜 명세에 맞춰 고정한다.
     */
    private VanApprovalTcpRequest toTcpRequest(VanApproveRequest request) {
        return new VanApprovalTcpRequest(
                PROTOCOL_VERSION,
                APPROVAL_MESSAGE_TYPE,
                approvalRequestId(request),
                request.posTrx(),
                request.attemptSeq(),
                request.amount(),
                request.pan(),
                request.expiryYyMm()
        );
    }

    /**
     * TCP 승인 요청 전문 DTO를 JSON payload byte[]로 직렬화한다.
     * <p>
     * length header는 여기서 붙이지 않고 transport 계층의 Spring Integration serializer가 처리한다.
     */
    private byte[] writeRequest(VanApprovalTcpRequest tcpRequest) {
        try {
            return objectMapper.writeValueAsBytes(tcpRequest);
        } catch (JsonProcessingException e) {
            throw new TcpVanGatewayException("VAN_TCP_APPROVAL_REQUEST_SERIALIZE_FAILED", e);
        }
    }

    /**
     * 기존 업무 조회 요청 DTO를 VAN TCP 조회 요청 전문 DTO로 변환한다.
     * <p>
     * Release 4 TCP Inquiry 조회 key는 posTrx/attemptSeq이므로 업무 DTO의 vanTrxId/cardLast4는 전문에 싣지 않는다.
     */
    private VanInquiryTcpRequest toTcpRequest(VanInquiryRequest request) {
        return new VanInquiryTcpRequest(
                PROTOCOL_VERSION,
                INQUIRY_MESSAGE_TYPE,
                inquiryRequestId(request),
                request.posTrx(),
                request.attemptSeq()
        );
    }

    /**
     * TCP 조회 요청 전문 DTO를 JSON payload byte[]로 직렬화한다.
     * <p>
     * 반환값은 length header가 없는 JSON body다.
     * SpringIntegrationVanTcpClient 뒤의 TCP serializer가 실제 4-byte length header를 붙인다.
     */
    private byte[] writeRequest(VanInquiryTcpRequest tcpRequest) {
        try {
            return objectMapper.writeValueAsBytes(tcpRequest);
        } catch (JsonProcessingException e) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_REQUEST_SERIALIZE_FAILED", e);
        }
    }

    /**
     * VAN Simulator가 반환한 JSON payload byte[]를 TCP 승인 응답 전문 DTO로 역직렬화한다.
     */
    private VanApprovalTcpResponse readResponse(byte[] responsePayload) {
        try {
            return objectMapper.readValue(responsePayload, VanApprovalTcpResponse.class);
        } catch (IOException e) {
            throw new TcpVanGatewayException("VAN_TCP_APPROVAL_RESPONSE_DESERIALIZE_FAILED", e);
        }
    }

    /**
     * VAN Simulator가 반환한 JSON payload byte[]를 TCP 조회 응답 전문 DTO로 역직렬화한다.
     */
    private VanInquiryTcpResponse readInquiryResponse(byte[] responsePayload) {
        try {
            return objectMapper.readValue(responsePayload, VanInquiryTcpResponse.class);
        } catch (IOException e) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_DESERIALIZE_FAILED", e);
        }
    }

    /**
     * 요청과 응답이 같은 승인 거래를 가리키는지 검증한다.
     * <p>
     * 프로토콜 버전, 응답 messageType, requestId, posTrx, attemptSeq가 다르면
     * 다른 요청의 응답이거나 프로토콜 불일치이므로 업무 응답으로 변환하지 않는다.
     */
    private void validateResponse(
            VanApprovalTcpRequest tcpRequest,
            VanApprovalTcpResponse tcpResponse
    ) {
        if (!PROTOCOL_VERSION.equals(tcpResponse.protocolVersion())
                || !APPROVAL_RESPONSE_MESSAGE_TYPE.equals(tcpResponse.messageType())
                || !tcpRequest.requestId().equals(tcpResponse.requestId())
                || !tcpRequest.posTrx().equals(tcpResponse.posTrx())
                || tcpRequest.attemptSeq() != tcpResponse.attemptSeq()) {
            throw new TcpVanGatewayException("VAN_TCP_APPROVAL_RESPONSE_MISMATCH");
        }
    }

    /**
     * 요청과 응답이 같은 조회 거래를 가리키는지 검증한다.
     * <p>
     * protocolVersion/messageType은 VAN Simulator와 같은 TCP 계약을 보고 있는지 확인하는 값이다.
     * requestId/posTrx/attemptSeq는 응답 correlation 값이다.
     * 하나라도 다르면 다른 요청의 응답이거나 프로토콜 불일치이므로 Payment 업무 상태로 반영하지 않는다.
     */
    private void validateInquiryResponse(
            VanInquiryTcpRequest tcpRequest,
            VanInquiryTcpResponse tcpResponse
    ) {
        if (!PROTOCOL_VERSION.equals(tcpResponse.protocolVersion())
                || !INQUIRY_RESPONSE_MESSAGE_TYPE.equals(tcpResponse.messageType())
                || !tcpRequest.requestId().equals(tcpResponse.requestId())
                || !tcpRequest.posTrx().equals(tcpResponse.posTrx())
                || tcpRequest.attemptSeq() != tcpResponse.attemptSeq()) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
        }
    }

    /**
     * TCP 승인 응답 전문을 기존 Payment 업무 승인 응답 DTO로 변환한다.
     * <p>
     * 카드 BIN/last4는 VAN 응답에 없으므로 기존 요청 DTO에서 보존한다.
     */
    private VanApproveResponse toApproveResponse(
            VanApproveRequest request,
            VanApprovalTcpResponse tcpResponse
    ) {
        VanResult vanResult = toVanResult(tcpResponse.status());
        PaymentFinalStatus finalStatus = toFinalStatus(tcpResponse.status());

        return VanApproveResponse.builder()
                .posTrx(tcpResponse.posTrx())
                .attemptSeq(tcpResponse.attemptSeq())
                .cardBin(request.cardBin())
                .cardLast4(request.cardLast4())
                .vanResult(vanResult)
                .finalStatus(finalStatus)
                .approvalNo(tcpResponse.approvalNo())
                .declineCode(toDeclineCode(tcpResponse))
                .vanTrxId(tcpResponse.vanTrxId())
                .message(tcpResponse.status().name())
                .respondedAt(tcpResponse.respondedAt())
                .build();
    }

    /**
     * TCP 조회 응답 전문을 기존 Payment 업무 조회 응답 DTO로 변환한다.
     * <p>
     * 이 변환 결과는 PaymentInquiryServiceImpl이 UNKNOWN_TIMEOUT row를 복구할 때 바로 사용한다.
     * APPROVED/DECLINED는 DB의 UNKNOWN_TIMEOUT attempt를 최종 상태로 바꾸는 입력이 되고,
     * UNKNOWN은 기존 UNKNOWN_TIMEOUT 상태를 유지하게 만든다.
     * <p>
     * VAN 응답의 vanTrxId/approvalNo/declineCode는 여기서 버리지 않고 업무 DTO에 보존한다.
     * 그래야 Inquiry 이후 Payment DB가 VAN 원장의 approvalNo/vanTrxId로 복구될 수 있다.
     */
    private VanInquiryResponse toInquiryResponse(VanInquiryTcpResponse tcpResponse) {
        return VanInquiryResponse.builder()
                .posTrx(tcpResponse.posTrx())
                .attemptSeq(tcpResponse.attemptSeq())
                .finalStatus(toFinalStatus(tcpResponse.status()))
                .approvalNo(tcpResponse.approvalNo())
                .declineCode(toDeclineCode(tcpResponse))
                .vanTrxId(tcpResponse.vanTrxId())
                .message(tcpResponse.status().name())
                .respondedAt(tcpResponse.respondedAt())
                .build();
    }

    /**
     * VAN TCP 프로토콜 상태를 Payment 쪽 VAN 결과 enum으로 변환한다.
     */
    private VanResult toVanResult(VanApprovalStatus status) {
        return switch (status) {
            case APPROVED -> VanResult.APPROVED;
            case DECLINED -> VanResult.DECLINED;
            case UNKNOWN -> VanResult.TIMEOUT;
        };
    }

    /**
     * VAN TCP 프로토콜 상태를 Payment 최종 승인 상태로 변환한다.
     * <p>
     * VAN의 UNKNOWN은 Payment 정책상 UNKNOWN_TIMEOUT으로 저장/응답한다.
     */
    private PaymentFinalStatus toFinalStatus(VanApprovalStatus status) {
        return switch (status) {
            case APPROVED -> PaymentFinalStatus.APPROVED;
            case DECLINED -> PaymentFinalStatus.DECLINED;
            case UNKNOWN -> PaymentFinalStatus.UNKNOWN_TIMEOUT;
        };
    }

    /**
     * VAN TCP 조회 프로토콜 상태를 Payment 최종 승인 상태로 변환한다.
     * <p>
     * UNKNOWN을 APPROVED나 DECLINED로 추론하면 안 된다.
     * VAN이 아직 확정 상태를 알려주지 못했다는 뜻이므로,
     * Payment 관점의 기존 미확정 상태인 UNKNOWN_TIMEOUT으로 유지한다.
     */
    private PaymentFinalStatus toFinalStatus(VanInquiryStatus status) {
        return switch (status) {
            case APPROVED -> PaymentFinalStatus.APPROVED;
            case DECLINED -> PaymentFinalStatus.DECLINED;
            case UNKNOWN -> PaymentFinalStatus.UNKNOWN_TIMEOUT;
        };
    }

    /**
     * VAN TCP 응답의 declineCode 문자열을 Payment 내부 VanDeclineCode enum으로 변환한다.
     * <p>
     * 현재 VAN Simulator 승인 정상 흐름은 APPROVED 중심이지만,
     * DECLINED/UNKNOWN 응답도 기존 Payment 저장 규칙에 맞게 방어적으로 매핑한다.
     */
    private VanDeclineCode toDeclineCode(VanApprovalTcpResponse tcpResponse) {
        if (tcpResponse.status() == VanApprovalStatus.APPROVED) {
            return null;
        }

        if ("TIMEOUT".equals(tcpResponse.declineCode())
                || tcpResponse.status() == VanApprovalStatus.UNKNOWN) {
            return VanDeclineCode.TIMEOUT;
        }

        if ("INVALID_REQUEST".equals(tcpResponse.declineCode())) {
            return VanDeclineCode.INVALID_REQUEST;
        }

        return VanDeclineCode.DO_NOT_HONOR;
    }

    /**
     * VAN TCP 조회 응답의 declineCode 문자열을 Payment 내부 VanDeclineCode enum으로 변환한다.
     * <p>
     * 현재 내부 enum은 제한된 코드만 표현한다.
     * VAN이 "05" 또는 알 수 없는 거절 코드를 보내면 일반 거절인 DO_NOT_HONOR로 접고,
     * UNKNOWN 또는 TIMEOUT 문자열은 후속조회에서도 아직 미확정이라는 의미로 TIMEOUT에 매핑한다.
     */
    private VanDeclineCode toDeclineCode(VanInquiryTcpResponse tcpResponse) {
        if (tcpResponse.status() == VanInquiryStatus.APPROVED) {
            return null;
        }

        if ("TIMEOUT".equals(tcpResponse.declineCode())
                || tcpResponse.status() == VanInquiryStatus.UNKNOWN) {
            return VanDeclineCode.TIMEOUT;
        }

        if ("INVALID_REQUEST".equals(tcpResponse.declineCode())) {
            return VanDeclineCode.INVALID_REQUEST;
        }

        return VanDeclineCode.DO_NOT_HONOR;
    }

    /**
     * Payment Server가 생성하는 TCP 승인 요청 식별자다.
     * <p>
     * VAN Simulator는 requestId를 응답에 그대로 돌려주므로, posTrx/attemptSeq와 함께 응답 매칭에 사용한다.
     */
    private String approvalRequestId(VanApproveRequest request) {
        return "APPROVAL-" + request.posTrx() + "-" + request.attemptSeq();
    }

    /**
     * Payment Server가 생성하는 TCP 조회 요청 식별자다.
     * <p>
     * VAN Simulator는 requestId를 응답에 그대로 돌려준다.
     * Payment는 이 값과 posTrx/attemptSeq를 함께 비교해 응답이 현재 요청에 대한 것인지 검증한다.
     */
    private String inquiryRequestId(VanInquiryRequest request) {
        return "INQUIRY-" + request.posTrx() + "-" + request.attemptSeq();
    }
}
