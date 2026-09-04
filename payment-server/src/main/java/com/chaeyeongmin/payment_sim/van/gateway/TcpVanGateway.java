package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
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
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpResponse;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 기존 VanGateway 경계를 유지하면서 Payment 업무 DTO를 실제 TCP VAN Simulator 호출로 바꾸는 어댑터다.
 * <p>
 * 책임:
 * - 기존 업무 DTO({@link VanApproveRequest}, {@link VanApproveResponse}, {@link VanInquiryRequest}, {@link VanInquiryResponse},
 *   {@link VanCancelRequest}, {@link VanCancelResponse})와 TCP 전문 DTO 사이를 변환한다.
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
    private static final String CANCEL_MESSAGE_TYPE = "CANCEL";
    private static final String CANCEL_RESPONSE_MESSAGE_TYPE = "CANCEL_RESPONSE";
    private static final String REVERSAL_MESSAGE_TYPE = "REVERSAL";
    private static final String REVERSAL_RESPONSE_MESSAGE_TYPE = "REVERSAL_RESPONSE";

    private final ObjectMapper objectMapper;
    private final VanTcpClient vanTcpClient;

    /**
     * [A6] 기존 Payment 승인 요청을 TCP VAN Simulator 승인 호출로 변환한다.
     *
     * <p>
     * 공통 TCP 흐름:
     * 업무 DTO -> TCP request DTO -> JSON byte[] -> VanTcpClient -> JSON byte[] 응답
     * -> TCP response DTO -> 업무 DTO
     *
     * <p>
     * 승인에서는 PAN/expiry가 VAN Simulator로 전달되고,
     * 응답 correlation은 requestId + posTrx + attemptSeq로 검증한다.
     */
    @Override
    public VanApproveResponse approve(VanApproveRequest request) {
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

    /**
     * 기존 Payment 조회 요청을 TCP VAN Simulator Inquiry 호출로 변환한다.
     *
     * <p>
     * 공통 TCP 흐름:
     * 업무 DTO -> TCP request DTO -> JSON byte[] -> VanTcpClient -> JSON byte[] 응답
     * -> TCP response DTO -> 업무 DTO
     *
     * <p>
     * Inquiry에서는 조회 key인 posTrx/attemptSeq만 TCP 전문에 싣는다.
     * 업무 DTO의 vanTrxId/cardLast4는 응답 복구 검증용 힌트일 수 있지만 TCP 조회 계약에는 포함하지 않는다.
     */
    @Override
    public VanInquiryResponse inquiry(VanInquiryRequest request) {
        try {
            VanInquiryTcpRequest tcpRequest = toTcpRequest(request);
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanInquiryTcpResponse tcpResponse = readInquiryResponse(responsePayload);

            validateInquiryResponse(tcpRequest, tcpResponse);
            return toInquiryResponse(tcpResponse);

        } catch (VanTcpRequestNotSentException e) {
            throw new VanGatewayRequestNotSentException(e);
        } catch (VanTcpResponseTimeoutException e) {
            throw new VanGatewayTimeoutException(e);
        }
    }

    /**
     * 기존 Payment 취소 요청을 TCP VAN Simulator 취소 호출로 변환한다.
     *
     * <p>
     * 공통 TCP 흐름:
     * 업무 DTO -> TCP request DTO -> JSON byte[] -> VanTcpClient -> JSON byte[] 응답
     * -> TCP response DTO -> 업무 DTO
     *
     * <p>
     * 취소에서는 cardLast4를 TCP 전문에 싣지 않고, cancelPosTrx와 원승인 거래 식별자만 전달한다.
     * 응답 correlation은 requestId + cancelPosTrx + originalPosTrx + originalAttemptSeq로 검증한다.
     */
    @Override
    public VanCancelResponse cancel(VanCancelRequest request) {
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

    /**
     * Payment Reversal 요청을 TCP VAN Simulator reversal 호출로 변환한다.
     *
     * <p>
     * Reversal은 Release 5 TCP mode 전용 boundary다.
     * 요청 correlation은 requestId + reversalPosTrx + originalPosTrx + originalAttemptSeq로 검증한다.
     */
    @Override
    public VanReversalResponse reversal(VanReversalRequest request) {
        try {
            VanReversalTcpRequest tcpRequest = toTcpRequest(request);
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanReversalTcpResponse tcpResponse = readReversalResponse(responsePayload);

            validateReversalResponse(tcpRequest, tcpResponse);

            return toReversalResponse(tcpResponse);

        } catch (VanTcpRequestNotSentException e) {
            throw new VanGatewayRequestNotSentException(e);
        } catch (VanTcpResponseTimeoutException e) {
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
                request.targetType(),
                request.targetTrxNo(),
                request.targetAttemptSeq()
        );
    }

    /**
     * 기존 업무 취소 요청 DTO를 VAN TCP 취소 요청 전문 DTO로 변환한다.
     *
     * <p>
     * VanCancelRequest.posTrx는 TCP 계약에서 cancelPosTrx로 보내고,
     * 원승인 VAN 거래번호/승인번호는 originalVanTrxId/originalApprovalNo로 이름을 맞춘다.
     * cardLast4는 TCP Cancel 전문에 포함하지 않는다.
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
     * 기존 업무 reversal 요청 DTO를 VAN TCP reversal 요청 전문 DTO로 변환한다.
     */
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
     * TCP 취소 요청 전문 DTO를 JSON payload byte[]로 직렬화한다.
     *
     * <p>
     * 반환값은 length header가 없는 JSON body다.
     */
    private byte[] writeRequest(VanCancelTcpRequest tcpRequest) {
        try {
            return objectMapper.writeValueAsBytes(tcpRequest);
        } catch (JsonProcessingException e) {
            throw new TcpVanGatewayException(
                    "VAN_TCP_CANCEL_REQUEST_SERIALIZE_FAILED",
                    e
            );
        }
    }

    /**
     * VAN Simulator가 반환한 JSON payload byte[]를 TCP 승인 응답 전문 DTO로 역직렬화한다.
     */
    private VanApprovalTcpResponse readApprovalResponse(byte[] responsePayload) {
        try {
            return objectMapper.readValue(responsePayload, VanApprovalTcpResponse.class);
        } catch (IOException e) {
            throw new TcpVanGatewayException("VAN_TCP_APPROVAL_RESPONSE_DESERIALIZE_FAILED", e);
        }
    }

    /**
     * TCP reversal 요청 전문 DTO를 JSON payload byte[]로 직렬화한다.
     */
    private byte[] writeRequest(VanReversalTcpRequest tcpRequest) {
        try {
            return objectMapper.writeValueAsBytes(tcpRequest);
        } catch (JsonProcessingException e) {
            throw new TcpVanGatewayException(
                    "VAN_TCP_REVERSAL_REQUEST_SERIALIZE_FAILED",
                    e
            );
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

    /**
     * 요청과 응답이 같은 조회 거래를 가리키는지 검증한다.
     * <p>
     * protocolVersion/messageType은 VAN Simulator와 같은 TCP 계약을 보고 있는지 확인하는 값이다.
     * requestId/targetType/targetTrxNo/targetAttemptSeq는 응답 correlation 값이다.
     * CANCEL 조회는 targetAttemptSeq가 null이므로 Objects.equals로 nullable 비교한다.
     * 하나라도 다르면 다른 요청의 응답이거나 프로토콜 불일치이므로 Payment 업무 상태로 반영하지 않는다.
     */
    private void validateInquiryResponse(
            VanInquiryTcpRequest tcpRequest,
            VanInquiryTcpResponse tcpResponse
    ) {
        if (PROTOCOL_VERSION.equals(tcpResponse.protocolVersion()) == false
                || INQUIRY_RESPONSE_MESSAGE_TYPE.equals(tcpResponse.messageType()) == false
                || tcpRequest.requestId().equals(tcpResponse.requestId()) == false
                || tcpRequest.targetType() != tcpResponse.targetType()
                || Objects.equals(tcpRequest.targetTrxNo(), tcpResponse.targetTrxNo()) == false
                || Objects.equals(tcpRequest.targetAttemptSeq(), tcpResponse.targetAttemptSeq()) == false) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
        }

        validateInquiryResult(tcpResponse);
    }

    private void validateInquiryResult(VanInquiryTcpResponse response) {
        if (response.resultCode() == null) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
        }

        switch (response.resultCode()) {
            case NOT_FOUND -> {
                // NOT_FOUND는 조회 대상 원장 row가 없다는 뜻이다.
                // status/승인번호/거절코드가 함께 오면 UNKNOWN이나 DECLINED와 구분할 수 없으므로 invalid다.
                if (response.status() != null
                        || response.vanTrxId() != null
                        || response.approvalNo() != null
                        || response.cancelApprovalNo() != null
                        || response.declineCode() != null) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }
            case SUCCESS -> validateInquirySuccessResult(response);
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

    private void validateInquirySuccessResult(VanInquiryTcpResponse response) {
        if (response.targetType() == null || response.status() == null) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
        }

        switch (response.targetType()) {
            case APPROVAL -> {
                // 승인 조회 성공은 승인 계열 status만 허용하고 cancelApprovalNo를 싣지 않는다.
                if (isApprovalInquiryStatus(response.status()) == false
                        || response.cancelApprovalNo() != null) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }
            case CANCEL -> {
                // 취소 조회 성공은 취소 계열 status만 허용하고 approvalNo를 싣지 않는다.
                if (isCancelInquiryStatus(response.status()) == false
                        || response.approvalNo() != null) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }
        }
    }

    /**
     * 요청과 응답이 같은 취소 거래를 가리키는지 검증한다.
     *
     * <p>
     * protocolVersion/messageType은 TCP 계약 검증값이고,
     * requestId/cancelPosTrx/originalPosTrx/originalAttemptSeq는 응답 correlation 값이다.
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
     * 요청과 응답이 같은 reversal 거래를 가리키는지 검증한다.
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
     * TCP Cancel 응답의 상태 조합이 업무적으로 유효한지 확인한다.
     *
     * <p>
     * SUCCESS/ALREADY_CANCELLED는 CANCELLED + cancelApprovalNo 조합이어야 하고,
     * 원승인 검증 실패 계열은 CANCEL_DECLINED + declineCode 조합이어야 한다.
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

            case ORIGINAL_NOT_FOUND,
                 ORIGINAL_NOT_APPROVED,
                 ORIGINAL_MISMATCH -> {
                if (response.cancelStatus() != VanCancelTcpStatus.CANCEL_DECLINED
                        || response.cancelApprovalNo() != null
                        || response.resultCode().name().equals(response.declineCode()) == false)
                    throw new TcpVanGatewayException("VAN_TCP_CANCEL_RESPONSE_INVALID");
            }
        }
    }

    /**
     * TCP Reversal 응답의 상태 조합이 업무적으로 유효한지 확인한다.
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

            case ORIGINAL_NOT_FOUND,
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
                .targetType(tcpResponse.targetType())
                .targetTrxNo(tcpResponse.targetTrxNo())
                .targetAttemptSeq(tcpResponse.targetAttemptSeq())
                .resultCode(tcpResponse.resultCode())
                .status(tcpResponse.status())
                .vanTrxId(tcpResponse.vanTrxId())
                .approvalNo(tcpResponse.approvalNo())
                .cancelApprovalNo(tcpResponse.cancelApprovalNo())
                .declineCode(toDeclineCode(tcpResponse))
                .message(inquiryMessage(tcpResponse))
                .respondedAt(tcpResponse.respondedAt())
                .build();
    }

    /**
     * TCP 취소 응답 전문을 기존 Payment 업무 취소 응답 DTO로 변환한다.
     *
     * <p>
     * TcpVanGateway 단계에서는 VanCancelResponse에 별도 resultCode 필드가 없으므로,
     * 현재 resultCode는 message에 보존한다.
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

    /**
     * TCP reversal 응답 전문을 Payment 업무 reversal 응답 DTO로 변환한다.
     */
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
            case CANCELLED,
                 CANCEL_DECLINED -> throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
        };
    }

    private boolean isApprovalInquiryStatus(VanInquiryStatus status) {
        return status == VanInquiryStatus.APPROVED
                || status == VanInquiryStatus.DECLINED
                || status == VanInquiryStatus.UNKNOWN;
    }

    /**
     * R5 공용 Inquiry protocol에서 CANCEL target이 가질 수 있는 성공 status 집합이다.
     */
    private boolean isCancelInquiryStatus(VanInquiryStatus status) {
        return status == VanInquiryStatus.CANCELLED
                || status == VanInquiryStatus.CANCEL_DECLINED;
    }

    /**
     * VAN TCP 취소 원장 상태를 Payment 취소 상태로 변환한다.
     */
    private CancelStatus toCancelStatus(VanCancelTcpStatus status) {
        return switch (status) {
            case CANCELLED -> CancelStatus.CANCELLED;
            case CANCEL_DECLINED -> CancelStatus.CANCEL_DECLINED;
        };
    }

    /**
     * VAN TCP reversal 원장 상태를 Payment reversal 상태로 변환한다.
     */
    private VanReversalStatus toReversalStatus(VanReversalTcpStatus status) {
        return switch (status) {
            case REVERSED -> VanReversalStatus.REVERSED;
            case REVERSAL_DECLINED -> VanReversalStatus.REVERSAL_DECLINED;
        };
    }

    /**
     * VAN TCP reversal resultCode를 Payment reversal resultCode로 변환한다.
     */
    private VanReversalResultCode toReversalResultCode(VanReversalTcpResultCode resultCode) {
        return switch (resultCode) {
            case SUCCESS -> VanReversalResultCode.SUCCESS;
            case ALREADY_REVERSED -> VanReversalResultCode.ALREADY_REVERSED;
            case ORIGINAL_NOT_FOUND -> VanReversalResultCode.ORIGINAL_NOT_FOUND;
            case ORIGINAL_NOT_REVERSIBLE -> VanReversalResultCode.ORIGINAL_NOT_REVERSIBLE;
            case ORIGINAL_MISMATCH -> VanReversalResultCode.ORIGINAL_MISMATCH;
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
        if (tcpResponse.resultCode() == VanInquiryResultCode.NOT_FOUND
                || tcpResponse.status() == VanInquiryStatus.APPROVED
                || tcpResponse.status() == VanInquiryStatus.CANCELLED) {
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

    private String inquiryMessage(VanInquiryTcpResponse response) {
        return response.resultCode() == VanInquiryResultCode.NOT_FOUND
                ? response.resultCode().name()
                : response.status().name();
    }

    /**
     * VAN TCP 취소 resultCode를 Payment 내부 VanDeclineCode로 변환한다.
     *
     * <p>
     * 정상 취소와 이미 취소된 원승인 재응답은 declineCode가 없고,
     * 원승인 검증 실패 계열은 같은 이름의 Payment decline enum으로 매핑한다.
     */
    private VanDeclineCode toDeclineCode(VanCancelTcpResponse response) {
        return switch (response.resultCode()) {
            case SUCCESS, ALREADY_CANCELLED -> null;

            case ORIGINAL_NOT_FOUND ->
                    VanDeclineCode.ORIGINAL_NOT_FOUND;

            case ORIGINAL_NOT_APPROVED ->
                    VanDeclineCode.ORIGINAL_NOT_APPROVED;

            case ORIGINAL_MISMATCH ->
                    VanDeclineCode.ORIGINAL_MISMATCH;
        };
    }

    /**
     * VAN TCP reversal resultCode를 Payment 내부 VanDeclineCode로 변환한다.
     */
    private VanDeclineCode toDeclineCode(VanReversalTcpResponse response) {
        return switch (response.resultCode()) {
            case SUCCESS, ALREADY_REVERSED -> null;

            case ORIGINAL_NOT_FOUND ->
                    VanDeclineCode.ORIGINAL_NOT_FOUND;

            case ORIGINAL_NOT_REVERSIBLE ->
                    VanDeclineCode.ORIGINAL_NOT_REVERSIBLE;

            case ORIGINAL_MISMATCH ->
                    VanDeclineCode.ORIGINAL_MISMATCH;
        };
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
        return "INQUIRY-" + request.targetType() + "-" + request.targetTrxNo()
                + "-" + nullToDash(request.targetAttemptSeq());
    }

    private String nullToDash(Integer value) {
        return value == null ? "null" : value.toString();
    }

    /**
     * Payment Server가 생성하는 TCP 취소 요청 식별자다.
     *
     * <p>
     * Cancel은 승인 attemptSeq를 새로 발급하는 업무가 아니므로, 현재 취소 거래번호인 posTrx만으로 requestId를 만든다.
     */
    private String cancelRequestId(VanCancelRequest request) {
        return "CANCEL-" + request.posTrx();
    }

    /**
     * Payment Server가 생성하는 TCP reversal 요청 식별자다.
     */
    private String reversalRequestId(VanReversalRequest request) {
        return "REVERSAL-" + request.reversalPosTrx();
    }

}
