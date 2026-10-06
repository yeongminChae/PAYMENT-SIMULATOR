package com.chaeyeongmin.payment_sim.van.gateway.impl.tcp;

import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpResultCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpStatus;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Payment 망취소 요청을 VAN TCP Reversal 프로토콜로 처리한다.
 *
 * <p>
 * Reversal 요청 전문 생성, 응답 correlation 검증,
 * 망취소 결과의 상태/필드 조합 검증과 Payment 업무 응답 변환을 담당한다.
 */
@Component
@ConditionalOnProperty(name = "payment.van.mode", havingValue = "tcp")
@RequiredArgsConstructor
@Slf4j
public class TcpReversalOperation {

    private static final String PROTOCOL_VERSION = "1";
    private static final String REVERSAL_MESSAGE_TYPE = "REVERSAL";
    private static final String REVERSAL_RESPONSE_MESSAGE_TYPE = "REVERSAL_RESPONSE";

    private final ObjectMapper objectMapper;
    private final VanTcpClient vanTcpClient;

    // ========================
    // Entry Point
    // ========================
    /**
     * 망취소 요청을 VAN TCP로 전송하고 검증된 결과를 반환한다.
     *
     * <p>
     * 응답은 requestId + reversalPosTrx + originalPosTrx + originalAttemptSeq로 검증한다.
     */
    public VanReversalResponse execute(VanReversalRequest request) {
        try {
            VanReversalTcpRequest tcpRequest = toTcpRequest(request);
            log.info("[van][reversal][request] vanRequestId={}, reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    tcpRequest.requestId(), tcpRequest.reversalPosTrx(), tcpRequest.originalPosTrx(), tcpRequest.originalAttemptSeq());
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanReversalTcpResponse tcpResponse = readReversalResponse(responsePayload);

            validateReversalResponse(tcpRequest, tcpResponse);
            VanReversalResponse response = toReversalResponse(tcpResponse);
            log.info("[van][reversal][response] vanRequestId={}, reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}, status={}, resultCode={}, vanTrxId={}, approvalNo={}, declineCode={}",
                    tcpRequest.requestId(), response.reversalPosTrx(), response.originalPosTrx(), response.originalAttemptSeq(),
                    response.reversalStatus(), response.resultCode(), response.vanReversalTrxId(),
                    response.reversalApprovalNo(), response.declineCode());
            return response;

        } catch (VanTcpRequestNotSentException e) {
            log.warn("[van][reversal][request-not-sent] vanRequestId={}, reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    reversalRequestId(request), request.reversalPosTrx(), request.originalPosTrx(), request.originalAttemptSeq(), e);
            throw new VanGatewayRequestNotSentException(e);
        } catch (VanTcpResponseTimeoutException e) {
            log.warn("[van][reversal][timeout] vanRequestId={}, reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    reversalRequestId(request), request.reversalPosTrx(), request.originalPosTrx(), request.originalAttemptSeq(), e);
            throw new VanGatewayTimeoutException(e);
        }
    }


    // ========================
    // Request Mapping
    // ========================
    /** Payment 망취소 요청을 VAN TCP Reversal 요청 전문으로 변환한다. */
    private VanReversalTcpRequest toTcpRequest(VanReversalRequest request) {
        return new VanReversalTcpRequest(
                PROTOCOL_VERSION,
                REVERSAL_MESSAGE_TYPE,
                reversalRequestId(request),
                request.reversalPosTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                request.amount()
        );
    }

    /** 망취소 거래번호를 기반으로 Reversal requestId를 생성한다. */
    private String reversalRequestId(VanReversalRequest request) {
        return "REVERSAL-" + request.reversalPosTrx();
    }

    // ========================
    // TCP Serialization
    // ========================
    /**
     * TCP reversal 요청 전문 DTO를 JSON payload byte[]로 직렬화한다.
     */
    private byte[] writeRequest(VanReversalTcpRequest tcpRequest) {
        try {
            return objectMapper.writeValueAsBytes(tcpRequest);
        } catch (JsonProcessingException e) {
            throw new TcpVanGatewayException("VAN_TCP_REVERSAL_REQUEST_SERIALIZE_FAILED", e);
        }
    }

    /**
     * VAN Simulator가 반환한 JSON payload byte[]를 TCP reversal 응답 전문 DTO로 역직렬화한다.
     */
    private VanReversalTcpResponse readReversalResponse(byte[] responsePayload) {
        try {
            return objectMapper.readValue(responsePayload, VanReversalTcpResponse.class);
        } catch (IOException e) {
            throw new TcpVanGatewayException("VAN_TCP_REVERSAL_RESPONSE_DESERIALIZE_FAILED", e);
        }
    }

    // ========================
    // Response Validation
    // ========================
    /**
     * Reversal 응답의 프로토콜 정보와 correlation 값을 검증한다.
     */
    private void validateReversalResponse(
            VanReversalTcpRequest request,
            VanReversalTcpResponse response
    ) {
        if (PROTOCOL_VERSION.equals(response.protocolVersion()) == false
                || REVERSAL_RESPONSE_MESSAGE_TYPE.equals(response.messageType()) == false
                || request.requestId().equals(response.requestId()) == false
                || request.reversalPosTrx().equals(response.reversalPosTrx()) == false
                || request.originalPosTrx().equals(response.originalPosTrx()) == false
                || request.originalAttemptSeq() != response.originalAttemptSeq()) {

            throw new TcpVanGatewayException("VAN_TCP_REVERSAL_RESPONSE_MISMATCH");
        }

        validateReversalResult(response);
    }

    /**
     * Reversal resultCode와 망취소 상태/결과 필드의 조합을 검증한다.
     *
     * <p>
     * SUCCESS/ALREADY_REVERSED는 REVERSED + reversalApprovalNo 조합이어야 하며,
     * 실패 계열은 REVERSAL_DECLINED 상태와 resultCode에 대응하는 declineCode를 가져야 한다.
     */
    private void validateReversalResult(VanReversalTcpResponse response) {
        switch (response.resultCode()) {
            case SUCCESS,
                 ALREADY_REVERSED -> {
                if (response.reversalStatus() != VanReversalTcpStatus.REVERSED
                        || response.reversalApprovalNo() == null
                        || response.reversalApprovalNo().isBlank()
                        || response.declineCode() != null) {
                    throw new TcpVanGatewayException("VAN_TCP_REVERSAL_RESPONSE_INVALID");
                }
            }

            case ALREADY_CANCELLED,
                 ORIGINAL_NOT_FOUND,
                 ORIGINAL_NOT_REVERSIBLE,
                 ORIGINAL_MISMATCH -> {
                if (response.reversalStatus() != VanReversalTcpStatus.REVERSAL_DECLINED
                        || response.reversalApprovalNo() != null
                        || response.resultCode().name().equals(response.declineCode()) == false) {
                    throw new TcpVanGatewayException("VAN_TCP_REVERSAL_RESPONSE_INVALID");
                }
            }

        }

    }

    // ========================
    // Response Mapping
    // ========================
    /** VAN TCP Reversal 응답을 Payment 망취소 응답으로 변환한다. */
    private VanReversalResponse toReversalResponse(VanReversalTcpResponse tcpResponse) {
        return VanReversalResponse.builder()
                .reversalPosTrx(tcpResponse.reversalPosTrx())
                .originalPosTrx(tcpResponse.originalPosTrx())
                .originalAttemptSeq(tcpResponse.originalAttemptSeq())
                .reversalStatus(toReversalStatus(tcpResponse.reversalStatus()))
                .resultCode(toReversalResultCode(tcpResponse.resultCode()))
                .reversalApprovalNo(tcpResponse.reversalApprovalNo())
                .declineCode(toDeclineCode(tcpResponse))
                .vanReversalTrxId(tcpResponse.vanReversalTrxId())
                .respondedAt(LocalDateTime.now())
                .build();
    }

    /** VAN Reversal 상태를 Payment 망취소 상태로 변환한다. */
    private VanReversalStatus toReversalStatus(VanReversalTcpStatus status) {
        return switch (status) {
            case REVERSED -> VanReversalStatus.REVERSED;
            case REVERSAL_DECLINED -> VanReversalStatus.REVERSAL_DECLINED;
        };
    }

    /** VAN Reversal resultCode를 Payment resultCode로 변환한다. */
    private VanReversalResultCode toReversalResultCode(VanReversalTcpResultCode resultCode) {
        return switch (resultCode) {
            case SUCCESS -> VanReversalResultCode.SUCCESS;
            case ALREADY_CANCELLED -> VanReversalResultCode.ALREADY_CANCELLED;
            case ALREADY_REVERSED -> VanReversalResultCode.ALREADY_REVERSED;
            case ORIGINAL_NOT_FOUND -> VanReversalResultCode.ORIGINAL_NOT_FOUND;
            case ORIGINAL_NOT_REVERSIBLE -> VanReversalResultCode.ORIGINAL_NOT_REVERSIBLE;
            case ORIGINAL_MISMATCH -> VanReversalResultCode.ORIGINAL_MISMATCH;
        };
    }

    /** VAN Reversal resultCode를 Payment declineCode로 변환한다. */
    private VanDeclineCode toDeclineCode(VanReversalTcpResponse response) {
        return switch (response.resultCode()) {
            case SUCCESS, ALREADY_REVERSED -> null;
            case ALREADY_CANCELLED -> VanDeclineCode.ALREADY_CANCELLED;
            case ORIGINAL_NOT_FOUND -> VanDeclineCode.ORIGINAL_NOT_FOUND;
            case ORIGINAL_NOT_REVERSIBLE -> VanDeclineCode.ORIGINAL_NOT_REVERSIBLE;
            case ORIGINAL_MISMATCH -> VanDeclineCode.ORIGINAL_MISMATCH;
        };
    }

}
