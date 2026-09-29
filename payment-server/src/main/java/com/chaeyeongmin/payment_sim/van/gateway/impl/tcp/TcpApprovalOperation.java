package com.chaeyeongmin.payment_sim.van.gateway.impl.tcp;

import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpResponse;
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
 * Payment 승인 요청을 VAN TCP 승인 프로토콜로 변환하고 응답을 해석한다.
 *
 * <p>
 * 요청 전문 생성, JSON 직렬화/역직렬화, 응답 correlation 검증,
 * TCP 프로토콜 상태를 Payment 업무 응답으로 변환하는 책임을 가진다.
 */
@Component
@ConditionalOnProperty(name = "payment.van.mode", havingValue = "tcp")
@RequiredArgsConstructor
public class TcpApprovalOperation {

    private static final String PROTOCOL_VERSION = "1";
    private static final String APPROVAL_MESSAGE_TYPE = "APPROVAL";
    private static final String APPROVAL_RESPONSE_MESSAGE_TYPE = "APPROVAL_RESPONSE";

    private final ObjectMapper objectMapper;
    private final VanTcpClient vanTcpClient;

    // ========================
    // Entry Point
    // ========================
    /**
     * 승인 요청을 VAN TCP로 전송하고 검증된 승인 결과를 Payment 업무 응답으로 반환한다.
     *
     * <p>
     * 응답은 requestId + posTrx + attemptSeq로 현재 요청과 동일한 거래인지 검증한다.
     */
    public VanApproveResponse execute(VanApproveRequest request) {
        try {
            VanApprovalTcpRequest tcpRequest = toTcpRequest(request);
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanApprovalTcpResponse tcpResponse = readApprovalResponse(responsePayload);

            validateApprovalResponse(tcpRequest, tcpResponse);
            return toApproveResponse(request, tcpResponse);

        } catch (VanTcpRequestNotSentException e) {
            throw new VanGatewayRequestNotSentException(e);
        } catch (VanTcpResponseTimeoutException e) {
            throw new VanGatewayTimeoutException(e);
        }
    }

    // ========================
    // Request Mapping
    // ========================
    /**
     * Payment 승인 요청을 VAN TCP 승인 요청 전문으로 변환한다.
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
     * 승인 요청의 correlation에 사용할 requestId를 생성한다.
     */
    private String approvalRequestId(VanApproveRequest request) {
        return "APPROVAL-" + request.posTrx() + "-" + request.attemptSeq();
    }

    // ========================
    // TCP Serialization
    // ========================
    /**
     * 승인 요청 전문을 TCP payload용 JSON byte[]로 직렬화한다.
     * length header는 transport 계층에서 처리한다.
     */
    private byte[] writeRequest(VanApprovalTcpRequest tcpRequest) {
        try {
            return objectMapper.writeValueAsBytes(tcpRequest);
        } catch (JsonProcessingException e) {
            throw new TcpVanGatewayException("VAN_TCP_APPROVAL_REQUEST_SERIALIZE_FAILED", e);
        }
    }

    /**
     * VAN 응답 payload를 승인 응답 전문으로 역직렬화한다.
     */
    private VanApprovalTcpResponse readApprovalResponse(byte[] responsePayload) {
        try {
            return objectMapper.readValue(responsePayload, VanApprovalTcpResponse.class);
        } catch (IOException e) {
            throw new TcpVanGatewayException("VAN_TCP_APPROVAL_RESPONSE_DESERIALIZE_FAILED", e);
        }
    }

    // ========================
    // Response Validation
    // ========================
    /**
     * 응답의 protocolVersion, messageType과 correlation 값을 검증한다.
     *
     * <p>
     * requestId + posTrx + attemptSeq가 요청과 다르면
     * 다른 거래의 응답으로 판단해 거부한다.
     */
    private void validateApprovalResponse(
            VanApprovalTcpRequest tcpRequest,
            VanApprovalTcpResponse tcpResponse
    ) {
        if (PROTOCOL_VERSION.equals(tcpResponse.protocolVersion()) == false
                || APPROVAL_RESPONSE_MESSAGE_TYPE.equals(tcpResponse.messageType()) == false
                || tcpRequest.requestId().equals(tcpResponse.requestId()) == false
                || tcpRequest.posTrx().equals(tcpResponse.posTrx()) == false
                || tcpRequest.attemptSeq() != tcpResponse.attemptSeq()) {

            throw new TcpVanGatewayException("VAN_TCP_APPROVAL_RESPONSE_MISMATCH");
        }
    }


    // ========================
    // Response Mapping
    // ========================
    /**
     * VAN TCP 승인 응답을 Payment 승인 응답으로 변환한다.
     *
     * <p>
     * VAN 응답에 없는 cardBin/cardLast4는 원 요청의 값을 유지한다.
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
     * VAN TCP 응답의 declineCode 문자열을
     * Payment 내부 VanDeclineCode enum으로 변환한다.
     * <p>
     * 현재 VAN Simulator 승인 정상 흐름은 APPROVED 중심이지만,
     * DECLINED/UNKNOWN 응답도 기존 Payment 저장 규칙에 맞게 방어적으로 매핑한다.
     */
    private VanDeclineCode toDeclineCode(VanApprovalTcpResponse response) {
        if (response.status() == VanApprovalStatus.APPROVED) {
            return null;
        }

        if ("TIMEOUT".equals(response.declineCode())
                || response.status() == VanApprovalStatus.UNKNOWN) {
            return VanDeclineCode.TIMEOUT;
        }

        if ("INVALID_REQUEST".equals(response.declineCode())) {
            return VanDeclineCode.INVALID_REQUEST;
        }

        return VanDeclineCode.DO_NOT_HONOR;
    }

}