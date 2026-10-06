package com.chaeyeongmin.van_sim.transaction.api.tcp.approval;

import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.scenario.application.approval.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.scenario.domain.approval.TransportBehavior;
import com.chaeyeongmin.van_sim.transaction.api.tcp.approval.exception.ApprovalTcpMessageException;
import com.chaeyeongmin.van_sim.transaction.api.tcp.support.PosTrxProtocolValidator;
import com.chaeyeongmin.van_sim.transaction.application.approval.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.application.approval.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.application.approval.service.ApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 승인 TCP 요청 전문을 승인 서비스에 연결하는 진입 핸들러다.
 * <p>
 * 수신한 JSON 바이트를 승인 요청 전문으로 역직렬화하고, 서비스 처리 결과를 다시 승인 응답 전문 바이트로 직렬화한다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
@Slf4j
public class ApprovalTcpHandler {

    /**
     * Approval TCP v1에서 이 handler가 처리할 수 있는 고정 protocol 식별자다.
     * dispatcher는 messageType만 보고 handler를 고르므로, 실제 side effect를 만들기 전
     * handler에서 protocolVersion까지 다시 확인한다.
     */
    private static final String PROTOCOL_VERSION = "1";
    private static final String MESSAGE_TYPE = "APPROVAL";

    private final ObjectMapper objectMapper;
    private final ApprovalTcpMessageMapper mapper;
    private final ApprovalService service;
    private final ApprovalScenarioRegistry registry;
    private final PosTrxProtocolValidator validator;

    /**
     * 승인 TCP 요청 payload를 처리하고 응답 payload를 반환한다.
     */
    public byte[] handle(byte[] payload) {
        // TCP 서버가 수신한 원본 JSON 바이트 payload를 승인 요청 전문 객체로 역직렬화한다.
        ApprovalRequestMessage approvalRequest = readApprovalRequest(payload);
        log.info("[van-tcp][approval][received] requestId={}, posTrx={}, attemptSeq={}",
                approvalRequest.requestId(), approvalRequest.posTrx(), approvalRequest.attemptSeq());

        // 취소 요청 전문 객체 값 체크
        validate(approvalRequest);

        // 승인 요청 전문에 담긴 거래 정보를 서비스 계층이 처리할 수 있는 커맨드 모델로 변환한다.
        ApprovalCommand approvalCommand = mapper.toCommand(approvalRequest);

        // 승인 서비스에 커맨드를 전달해 카드 승인 가능 여부와 응답에 필요한 처리 결과를 계산한다.
        // 이 호출이 반환된 시점에는 ApprovalService의 @Transactional 경계가 끝나 원장 저장도 commit된 뒤다.
        ApprovalResult approvalResult = service.processApproval(approvalCommand);
        log.info("[van-tcp][approval][result] requestId={}, posTrx={}, attemptSeq={}, status={}, vanTrxId={}, approvalNo={}, declineCode={}",
                approvalRequest.requestId(), approvalResult.posTrx(), approvalResult.attemptSeq(), approvalResult.status(),
                approvalResult.vanTrxId(), approvalResult.approvalNo(), approvalResult.declineCode());

        // DROP_RESPONSE는 발급사 승인 처리는 끝내되 TCP 응답만 유실시키는 transport 계층 시나리오다.
        // 따라서 서비스 트랜잭션 안에 넣지 않고, 업무 처리 완료 후 응답 payload를 만들기 전에 적용한다.
        if (shouldDropResponse(approvalRequest)) {
            log.warn("[van-tcp][approval][drop-response] requestId={}, posTrx={}, attemptSeq={}, result={}",
                    approvalRequest.requestId(), approvalRequest.posTrx(), approvalRequest.attemptSeq(), approvalResult.status());
            return null;
        }

        // 원 요청 전문의 식별 정보와 서비스 처리 결과를 조합해 TCP 응답 전문 객체를 만든다.
        ApprovalResponseMessage approvalResponse = mapper.toResponse(approvalRequest, approvalResult);

        // 응답 전문 객체를 TCP 클라이언트로 되돌려 보낼 JSON 바이트 payload로 직렬화한다.
        return writeApprovalResponse(approvalResponse);
    }

    /**
     * 수신 payload를 승인 요청 전문으로 변환한다.
     */
    private ApprovalRequestMessage readApprovalRequest(byte[] payload) {
        try {
            return objectMapper.readValue(payload, ApprovalRequestMessage.class);
        } catch (IOException e) {
            throw new ApprovalTcpMessageException(
                    "APPROVAL_TCP_REQUEST_DESERIALIZE_FAILED",
                    e
            );
        }

    }

    /**
     * 승인 요청 전문의 최소 프로토콜 계약을 검증한다.
     * <p>
     * 이 검증은 TCP boundary 방어용이다. 서비스 커맨드 변환 전에 실행해서
     * 지원하지 않는 전문이나 malformed PAN/expiry가 VAN 원장 처리로 내려가지 않게 막는다.
     */
    private void validate(ApprovalRequestMessage request) {
        if (PROTOCOL_VERSION.equals(request.protocolVersion()) == false
                || MESSAGE_TYPE.equals(request.messageType()) == false
                || isBlank(request.requestId())
                || validator.isInvalid(request.posTrx())
                || request.attemptSeq() <= 0
                || request.amount() <= 0
                || isInvalidPan(request.pan())
                || isInvalidExpiry(request.expiryYyMm())) {
            throw new ApprovalTcpMessageException("APPROVAL_TCP_REQUEST_INVALID");
        }
    }

    /**
     * mapper는 PAN에서 cardBin/cardLast4를 substring으로 파생한다.
     * null, 짧은 값, 숫자가 아닌 값은 mapper에서 런타임 예외가 나기 전에 invalid 전문으로 처리한다.
     */
    private boolean isInvalidPan(String pan) {
        return pan == null || pan.length() != 16 || isNumeric(pan) == false;
    }

    /**
     * expiryYyMm은 VAN 원장에 저장하지 않지만 승인 요청 전문의 필수 필드다.
     * 여기서는 TCP protocol 형식만 검증하고, 카드 만료 여부 같은 업무 판정은 Payment 입력 검증에 둔다.
     */
    private boolean isInvalidExpiry(String expiryYyMm) {
        if (expiryYyMm == null || expiryYyMm.length() != 4 || isNumeric(expiryYyMm) == false) {
            return true;
        }

        int month = Integer.parseInt(expiryYyMm.substring(2, 4));
        return month < 1 || month > 12;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }

        for (char c : value.toCharArray()) {
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 승인 응답 전문을 송신 payload로 변환한다.
     */
    private byte[] writeApprovalResponse(ApprovalResponseMessage response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (IOException e) {
            throw new ApprovalTcpMessageException(
                    "APPROVAL_TCP_RESPONSE_SERIALIZE_FAILED",
                    e
            );
        }

    }

    private boolean shouldDropResponse(ApprovalRequestMessage request) {
        return registry.find(request.posTrx())
                .map(scenario -> scenario.transportBehavior() == TransportBehavior.DROP_RESPONSE)
                .orElse(false);
    }
}
