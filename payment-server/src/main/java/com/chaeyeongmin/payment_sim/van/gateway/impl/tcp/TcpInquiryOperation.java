package com.chaeyeongmin.payment_sim.van.gateway.impl.tcp;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpResponse;
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
import java.util.Objects;

/**
 * Payment 조회 요청을 VAN TCP Inquiry 프로토콜로 처리한다.
 *
 * <p>
 * Approval, Cancel, Reversal 조회를 하나의 Inquiry 프로토콜로 처리하며,
 * 조회 대상별 허용 상태와 응답 필드 조합을 검증한 뒤
 * Payment 업무 응답으로 변환한다.
 */
@Component
@ConditionalOnProperty(name = "payment.van.mode", havingValue = "tcp")
@RequiredArgsConstructor
@Slf4j
public class TcpInquiryOperation {

    private static final String PROTOCOL_VERSION = "1";
    private static final String INQUIRY_MESSAGE_TYPE = "INQUIRY";
    private static final String INQUIRY_RESPONSE_MESSAGE_TYPE = "INQUIRY_RESPONSE";

    private final ObjectMapper objectMapper;
    private final VanTcpClient vanTcpClient;

    // ========================
    // Entry Point
    // ========================
    /**
     * Inquiry 요청을 VAN TCP로 전송하고 검증된 조회 결과를 반환한다.
     *
     * <p>
     * requestId, targetType, targetTrxNo, targetAttemptSeq로
     * 요청과 응답의 correlation을 검증한다.
     */
    public VanInquiryResponse execute(VanInquiryRequest request) {
        try {
            VanInquiryTcpRequest tcpRequest = toTcpRequest(request);
            log.info("[van][inquiry][request] vanRequestId={}, targetType={}, targetTrxNo={}, targetAttemptSeq={}",
                    tcpRequest.requestId(), tcpRequest.targetType(), tcpRequest.targetTrxNo(), tcpRequest.targetAttemptSeq());
            byte[] requestPayload = writeRequest(tcpRequest);
            byte[] responsePayload = vanTcpClient.send(requestPayload);
            VanInquiryTcpResponse tcpResponse = readInquiryResponse(responsePayload);

            validateInquiryResponse(tcpRequest, tcpResponse);
            VanInquiryResponse response = toInquiryResponse(tcpResponse);
            log.info("[van][inquiry][response] vanRequestId={}, targetType={}, targetTrxNo={}, targetAttemptSeq={}, resultCode={}, status={}, vanTrxId={}, approvalNo={}, cancelApprovalNo={}, reversalApprovalNo={}, declineCode={}",
                    tcpRequest.requestId(), response.targetType(), response.targetTrxNo(), response.targetAttemptSeq(),
                    response.resultCode(), response.status(), response.vanTrxId(), response.approvalNo(),
                    response.cancelApprovalNo(), response.reversalApprovalNo(), response.declineCode());
            return response;

        } catch (VanTcpRequestNotSentException e) {
            log.warn("[van][inquiry][request-not-sent] vanRequestId={}, targetType={}, targetTrxNo={}, targetAttemptSeq={}",
                    inquiryRequestId(request), request.targetType(), request.targetTrxNo(), request.targetAttemptSeq(), e);
            throw new VanGatewayRequestNotSentException(e);
        } catch (VanTcpResponseTimeoutException e) {
            log.warn("[van][inquiry][timeout] vanRequestId={}, targetType={}, targetTrxNo={}, targetAttemptSeq={}",
                    inquiryRequestId(request), request.targetType(), request.targetTrxNo(), request.targetAttemptSeq(), e);
            throw new VanGatewayTimeoutException(e);
        }
    }

    // ========================
    // Request Mapping
    // ========================
    /**
     * Payment Inquiry 요청을 VAN TCP 조회 전문으로 변환한다.
     *
     * <p>
     * 조회 key는 targetType + targetTrxNo + targetAttemptSeq이며,
     * Payment 내부의 복구용 정보는 TCP 요청 전문에 포함하지 않는다.
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

    /** Inquiry 요청의 correlation에 사용할 requestId를 생성한다. */
    private String inquiryRequestId(VanInquiryRequest request) {
        return "INQUIRY-" + request.targetType() + "-" + request.targetTrxNo()
                + "-" + nullToDash(request.targetAttemptSeq());
    }

    /** nullable attemptSeq를 requestId 문자열로 변환한다. */
    private String nullToDash(Integer value) {
        return value == null ? "null" : value.toString();
    }

    // ========================
    // TCP Serialization
    // ========================
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
     * VAN Simulator가 반환한 JSON payload byte[]를 TCP 조회 응답 전문 DTO로 역직렬화한다.
     */
    private VanInquiryTcpResponse readInquiryResponse(byte[] responsePayload) {
        try {
            return objectMapper.readValue(responsePayload, VanInquiryTcpResponse.class);
        } catch (IOException e) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_DESERIALIZE_FAILED", e);
        }
    }

    // ========================
    // Response Validation
    // ========================
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
                || Objects.equals(tcpRequest.targetTrxNo(), tcpResponse.targetTrxNo()) == false
                || Objects.equals(tcpRequest.targetAttemptSeq(), tcpResponse.targetAttemptSeq()) == false
                || tcpRequest.targetType() != tcpResponse.targetType()
        ) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
        }

        validateInquiryResult(tcpResponse);
    }

    /**
     * Inquiry 결과 코드에 맞는 필드 조합인지 확인한다.
     * NOT_FOUND 응답에 거래 상태나 승인 정보가 섞여 있으면 어떤 사실을 믿어야 할지 모호하므로 거부한다.
     */
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
                        || response.reversalApprovalNo() != null
                        || response.declineCode() != null
                ) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }

            case SUCCESS -> validateInquirySuccessResult(response);
        }
    }

    /**
     * SUCCESS 응답이 조회 대상 종류에 맞는 상태와 결과 필드를 가지고 있는지 확인한다.
     * 승인, 취소, 망취소의 상태가 서로 섞인 응답은 Payment DB 복구에 사용하지 않는다.
     */
    private void validateInquirySuccessResult(VanInquiryTcpResponse response) {
        if (response.targetType() == null || response.status() == null) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
        }

        switch (response.targetType()) {
            case APPROVAL -> {
                // 승인 조회 성공은 승인 계열 status만 허용하고 cancelApprovalNo를 싣지 않는다.
                if (isApprovalInquiryStatus(response.status()) == false
                        || response.cancelApprovalNo() != null
                        || response.reversalApprovalNo() != null) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }

            case CANCEL -> {
                // 취소 조회 성공은 취소 계열 status만 허용하고 approvalNo를 싣지 않는다.
                if (isCancelInquiryStatus(response.status()) == false
                        || response.approvalNo() != null
                        || response.reversalApprovalNo() != null
                ) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }

            case REVERSAL -> validateReversalInquirySuccessResult(response);

        }
    }

    /**
     * Reversal Inquiry SUCCESS 응답의 상태와 결과 필드 조합을 검증한다.
     *
     * <p>
     * REVERSED는 reversalApprovalNo가 필요하고 declineCode가 없어야 하며,
     * REVERSAL_DECLINED는 승인번호 없이 declineCode가 존재해야 한다.
     */
    private void validateReversalInquirySuccessResult(VanInquiryTcpResponse response) {
        if (isReversalInquiryStatus(response.status()) == false
                || response.approvalNo() != null
                || response.cancelApprovalNo() != null
                || response.vanTrxId() == null
                || response.vanTrxId().isBlank()) {
            throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
        }

        switch (response.status()) {
            case REVERSED -> {
                if (response.reversalApprovalNo() == null
                        || response.reversalApprovalNo().isBlank()
                        || response.declineCode() != null
                ) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }

            case REVERSAL_DECLINED -> {
                if (response.reversalApprovalNo() != null
                        || response.declineCode() == null
                        || response.declineCode().isBlank()
                ) {
                    throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
                }
            }

            default -> throw new TcpVanGatewayException("VAN_TCP_INQUIRY_RESPONSE_INVALID");
        }

    }

    /** Approval Inquiry에서 허용되는 성공 상태인지 확인한다. */
    private boolean isApprovalInquiryStatus(VanInquiryStatus status) {
        return status == VanInquiryStatus.APPROVED || status == VanInquiryStatus.DECLINED || status == VanInquiryStatus.UNKNOWN;
    }

    /** Cancel Inquiry에서 허용되는 성공 상태인지 확인한다. */
    private boolean isCancelInquiryStatus(VanInquiryStatus status) {
        return status == VanInquiryStatus.CANCELLED || status == VanInquiryStatus.CANCEL_DECLINED;
    }

    /** Reversal Inquiry에서 허용되는 성공 상태인지 확인한다. */
    private boolean isReversalInquiryStatus(VanInquiryStatus status) {
        return status == VanInquiryStatus.REVERSED || status == VanInquiryStatus.REVERSAL_DECLINED;
    }

    // ========================
    // Response Mapping
    // ========================
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
                .reversalApprovalNo(tcpResponse.reversalApprovalNo())
                .declineCode(toDeclineCode(tcpResponse))
                .message(inquiryMessage(tcpResponse))
                .respondedAt(tcpResponse.respondedAt())
                .build();
    }

    /**
     * Inquiry 응답의 declineCode를 Payment 내부 코드로 변환한다.
     *
     * <p>
     * UNKNOWN/TIMEOUT은 TIMEOUT으로,
     * 알 수 없는 거절 코드는 DO_NOT_HONOR로 정규화한다.
     */
    private VanDeclineCode toDeclineCode(VanInquiryTcpResponse response) {
        if (response.resultCode() == VanInquiryResultCode.NOT_FOUND
                || response.status() == VanInquiryStatus.APPROVED
                || response.status() == VanInquiryStatus.CANCELLED
                || response.status() == VanInquiryStatus.REVERSED
        ) {
            return null;
        }

        if (response.status() == VanInquiryStatus.UNKNOWN) return VanDeclineCode.TIMEOUT;

        return switch (response.declineCode()) {
            case "TIMEOUT" -> VanDeclineCode.TIMEOUT;
            case "INVALID_REQUEST" -> VanDeclineCode.INVALID_REQUEST;
            case "ALREADY_CANCELLED" -> VanDeclineCode.ALREADY_CANCELLED;
            case "ALREADY_REVERSED" -> VanDeclineCode.ALREADY_REVERSED;
            case "ORIGINAL_NOT_FOUND" -> VanDeclineCode.ORIGINAL_NOT_FOUND;
            case "ORIGINAL_NOT_APPROVED" -> VanDeclineCode.ORIGINAL_NOT_APPROVED;
            case "ORIGINAL_NOT_REVERSIBLE" -> VanDeclineCode.ORIGINAL_NOT_REVERSIBLE;
            case "ORIGINAL_MISMATCH" -> VanDeclineCode.ORIGINAL_MISMATCH;
            case null, default -> VanDeclineCode.DO_NOT_HONOR;
        };

    }

    /**
     * NOT_FOUND는 resultCode를, 조회 성공은 확인된 거래 상태를 메시지로 사용한다.
     */
    private String inquiryMessage(VanInquiryTcpResponse response) {
        return response.resultCode() == VanInquiryResultCode.NOT_FOUND
                ? response.resultCode().name()
                : response.status().name();
    }

}
