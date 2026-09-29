package com.chaeyeongmin.payment_sim.van.gateway.impl.tcp;

import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpStatus;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Payment 취소 요청을 VAN TCP Cancel 프로토콜로 처리한다.
 *
 * <p>
 * 취소 요청 전문 생성, 응답 correlation 검증,
 * 취소 결과의 상태/필드 조합 검증과 Payment 업무 응답 변환을 담당한다.
 */
@Component
@ConditionalOnProperty(name = "payment.van.mode", havingValue = "tcp")
@RequiredArgsConstructor
public class TcpCancelOperation {

    private static final String PROTOCOL_VERSION = "1";
    private static final String CANCEL_MESSAGE_TYPE = "CANCEL";
    private static final String CANCEL_RESPONSE_MESSAGE_TYPE = "CANCEL_RESPONSE";

    private final ObjectMapper objectMapper;
    private final VanTcpClient vanTcpClient;

    // ========================
    // Entry Point
    // ========================
    /**
     * 취소 요청을 VAN TCP로 전송하고 검증된 취소 결과를 반환한다.
     *
     * <p>
     * 응답은 requestId + cancelPosTrx + originalPosTrx + originalAttemptSeq로 검증한다.
     */
    public VanCancelResponse execute(VanCancelRequest request) {
        try {
            VanCancelTcpRequest tcpRequest = toTcpRequest(request);
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanCancelTcpResponse tcpResponse = readCancelResponse(responsePayload);

            validateCancelResponse(tcpRequest, tcpResponse);
            return toCancelResponse(tcpResponse);

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
     * Payment 취소 요청을 VAN TCP Cancel 요청 전문으로 변환한다.
     *
     * <p>
     * 현재 취소 거래번호와 원승인 거래 식별정보를 전달하며,
     * cardLast4는 Cancel TCP 계약에 포함하지 않는다.
     */
    private VanCancelTcpRequest toTcpRequest(VanCancelRequest request) {
        return new VanCancelTcpRequest(
                PROTOCOL_VERSION,
                CANCEL_MESSAGE_TYPE,
                cancelRequestId(request),
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                request.vanTrxId(),
                request.approvalNo(),
                request.amount()
        );
    }

    /**
     * 취소 거래번호를 기반으로 Cancel requestId를 생성한다.
     */
    private String cancelRequestId(VanCancelRequest request) {
        return "CANCEL-" + request.posTrx();
    }

    // ========================
    // TCP Serialization
    // ========================
    /**
     * TCP 취소 요청 전문 DTO를 JSON payload byte[]로 직렬화한다.
     *
     * <p>
     * 반환값은 length header가 없는 JSON body다.
     */
    private byte[] writeRequest(VanCancelTcpRequest tcpRequest) {
        try {
            return objectMapper.writeValueAsBytes(tcpRequest);
        } catch (JsonProcessingException e) {
            throw new TcpVanGatewayException("VAN_TCP_CANCEL_REQUEST_SERIALIZE_FAILED", e);
        }
    }

    /**
     * VAN Simulator가 반환한 JSON payload byte[]를 TCP 취소 응답 전문 DTO로 역직렬화한다.
     */
    private VanCancelTcpResponse readCancelResponse(byte[] responsePayload) {
        try {
            return objectMapper.readValue(responsePayload, VanCancelTcpResponse.class);
        } catch (IOException e) {
            throw new TcpVanGatewayException("VAN_TCP_CANCEL_RESPONSE_DESERIALIZE_FAILED", e);
        }
    }

    // ========================
    // Response Validation
    // ========================
    /**
     * Cancel 응답의 프로토콜 정보와 correlation 값을 검증한다.
     */
    private void validateCancelResponse(
            VanCancelTcpRequest request,
            VanCancelTcpResponse response
    ) {
        if (PROTOCOL_VERSION.equals(response.protocolVersion()) == false
                || CANCEL_RESPONSE_MESSAGE_TYPE.equals(response.messageType()) == false
                || request.requestId().equals(response.requestId()) == false
                || request.cancelPosTrx().equals(response.cancelPosTrx()) == false
                || request.originalPosTrx().equals(response.originalPosTrx()) == false
                || request.originalAttemptSeq() != response.originalAttemptSeq()) {

            throw new TcpVanGatewayException("VAN_TCP_CANCEL_RESPONSE_MISMATCH");
        }

        validateCancelResult(response);
    }

    /**
     * Cancel resultCode와 취소 상태/결과 필드의 조합을 검증한다.
     *
     * <p>
     * SUCCESS/ALREADY_CANCELLED는 CANCELLED + cancelApprovalNo 조합이어야 하며,
     * 실패 계열은 CANCEL_DECLINED 상태와 resultCode에 대응하는 declineCode를 가져야 한다.
     */
    private void validateCancelResult(VanCancelTcpResponse response) {
        switch (response.resultCode()) {
            case SUCCESS,
                 ALREADY_CANCELLED -> {
                if (response.cancelStatus() != VanCancelTcpStatus.CANCELLED
                        || response.cancelApprovalNo() == null
                        || response.cancelApprovalNo().isBlank()
                        || response.declineCode() != null)
                    throw new TcpVanGatewayException("VAN_TCP_CANCEL_RESPONSE_INVALID");
            }

            case ALREADY_REVERSED,
                 ORIGINAL_NOT_FOUND,
                 ORIGINAL_NOT_APPROVED,
                 ORIGINAL_MISMATCH -> {
                if (response.cancelStatus() != VanCancelTcpStatus.CANCEL_DECLINED
                        || response.cancelApprovalNo() != null
                        || response.resultCode().name().equals(response.declineCode()) == false)
                    throw new TcpVanGatewayException("VAN_TCP_CANCEL_RESPONSE_INVALID");
            }

        }

    }

    // ========================
    // Response Mapping
    // ========================
    /**
     * VAN TCP Cancel 응답을 Payment 취소 응답으로 변환한다.
     *
     * <p>
     * VanCancelResponse에는 별도 resultCode가 없으므로
     * TCP resultCode는 message에 보존한다.
     */
    private VanCancelResponse toCancelResponse(VanCancelTcpResponse tcpResponse) {
        return VanCancelResponse.builder()
                .posTrx(tcpResponse.cancelPosTrx())
                .originalPosTrx(tcpResponse.originalPosTrx())
                .originalAttemptSeq(tcpResponse.originalAttemptSeq())
                .cancelStatus(toCancelStatus(tcpResponse.cancelStatus()))
                .cancelApprovalNo(tcpResponse.cancelApprovalNo())
                .declineCode(toDeclineCode(tcpResponse))
                .vanTrxId(tcpResponse.vanCancelTrxId())
                .message(tcpResponse.resultCode().name())
                .respondedAt(LocalDateTime.now())
                .build();
    }


    /** VAN Cancel 상태를 Payment 취소 상태로 변환한다. */
    private CancelStatus toCancelStatus(VanCancelTcpStatus status) {
        return switch (status) {
            case CANCELLED -> CancelStatus.CANCELLED;
            case CANCEL_DECLINED -> CancelStatus.CANCEL_DECLINED;
        };
    }

    /** VAN Cancel resultCode를 Payment declineCode로 변환한다. */
    private VanDeclineCode toDeclineCode(VanCancelTcpResponse response) {
        return switch (response.resultCode()) {
            case SUCCESS, ALREADY_CANCELLED -> null;
            case ALREADY_REVERSED -> VanDeclineCode.ALREADY_REVERSED;
            case ORIGINAL_NOT_FOUND -> VanDeclineCode.ORIGINAL_NOT_FOUND;
            case ORIGINAL_NOT_APPROVED -> VanDeclineCode.ORIGINAL_NOT_APPROVED;
            case ORIGINAL_MISMATCH -> VanDeclineCode.ORIGINAL_MISMATCH;
        };
    }

}