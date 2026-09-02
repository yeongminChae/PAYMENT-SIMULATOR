package com.chaeyeongmin.van_sim.transaction.approval.tcp;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.transaction.approval.service.ApprovalService;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.approval.tcp.exception.ApprovalTcpMessageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * 승인 TCP 요청 전문을 승인 서비스에 연결하는 진입 핸들러다.
 * <p>
 * 수신한 JSON 바이트를 승인 요청 전문으로 역직렬화하고, 서비스 처리 결과를 다시 승인 응답 전문 바이트로 직렬화한다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
public class ApprovalTcpHandler {

    /**
     * Approval TCP v1에서 이 handler가 처리할 수 있는 고정 protocol 식별자다.
     * dispatcher는 messageType만 보고 handler를 고르므로, 실제 side effect를 만들기 전
     * handler에서 protocolVersion까지 다시 확인한다.
     */
    private static final String PROTOCOL_VERSION = "1";
    private static final String MESSAGE_TYPE = "APPROVAL";

    /**
     * Payment Server가 발급하는 POS 거래번호 형식이다.
     * storeCd(4)-bizDate(8)-posNo(4)-seq(4) 구조이며, VAN 원장 멱등 key의 일부로 쓰인다.
     */
    private static final Pattern POS_TRX_PATTERN = Pattern.compile("^\\d{4}-\\d{8}-\\d{4}-\\d{4}$");

    /**
     * posTrx 안의 bizDate가 20260230 같은 허위 날짜로 들어오는 것을 막기 위한 strict parser다.
     */
    private static final DateTimeFormatter BIZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private final ObjectMapper objectMapper;
    private final ApprovalTcpMessageMapper tcpMessageMapper;
    private final ApprovalService approvalService;
    private final ApprovalScenarioRegistry scenarioRegistry;

    /**
     * 승인 TCP 요청 payload를 처리하고 응답 payload를 반환한다.
     */
    public byte[] handle(byte[] payload) {
        // TCP 서버가 수신한 원본 JSON 바이트 payload를 승인 요청 전문 객체로 역직렬화한다.
        ApprovalRequestMessage approvalRequest = readApprovalRequest(payload);

        // 취소 요청 전문 객체 값 체크
        validate(approvalRequest);

        // 승인 요청 전문에 담긴 거래 정보를 서비스 계층이 처리할 수 있는 커맨드 모델로 변환한다.
        ApprovalCommand approvalCommand = tcpMessageMapper.toCommand(approvalRequest);

        // 승인 서비스에 커맨드를 전달해 카드 승인 가능 여부와 응답에 필요한 처리 결과를 계산한다.
        // 이 호출이 반환된 시점에는 ApprovalService의 @Transactional 경계가 끝나 원장 저장도 commit된 뒤다.
        ApprovalResult approvalResult = approvalService.processApproval(approvalCommand);

        // DROP_RESPONSE는 발급사 승인 처리는 끝내되 TCP 응답만 유실시키는 transport 계층 시나리오다.
        // 따라서 서비스 트랜잭션 안에 넣지 않고, 업무 처리 완료 후 응답 payload를 만들기 전에 적용한다.
        if (shouldDropResponse(approvalRequest)) return null;

        // 원 요청 전문의 식별 정보와 서비스 처리 결과를 조합해 TCP 응답 전문 객체를 만든다.
        ApprovalResponseMessage approvalResponse = tcpMessageMapper.toResponse(approvalRequest, approvalResult);

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
                || isInvalidPosTrx(request.posTrx())
                || request.attemptSeq() <= 0
                || request.amount() <= 0
                || isInvalidPan(request.pan())
                || isInvalidExpiry(request.expiryYyMm())) {
            throw new ApprovalTcpMessageException("APPROVAL_TCP_REQUEST_INVALID");
        }
    }

    /**
     * posTrx는 VAN 승인 원장의 멱등 key로 사용된다.
     * 형식이 깨진 값이 원장까지 내려가면 이후 Inquiry와 재응답 기준도 함께 흔들리므로
     * 서비스 호출 전에 protocol boundary에서 차단한다.
     */
    private boolean isInvalidPosTrx(String posTrx) {
        if (isBlank(posTrx) || POS_TRX_PATTERN.matcher(posTrx).matches() == false) return true;

        String bizDate = posTrx.substring(5, 13);

        try {
            LocalDate.parse(bizDate, BIZ_DATE_FORMATTER);

            return false;
        } catch (DateTimeParseException e) {
            return true;
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
        return scenarioRegistry.find(request.posTrx())
                .map(scenario -> scenario.transportBehavior() == TransportBehavior.DROP_RESPONSE)
                .orElse(false);
    }
}
