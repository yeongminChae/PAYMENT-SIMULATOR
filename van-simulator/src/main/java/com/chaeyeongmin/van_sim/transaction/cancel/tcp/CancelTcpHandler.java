package com.chaeyeongmin.van_sim.transaction.cancel.tcp;

import com.chaeyeongmin.van_sim.control.scenario.cancel.model.CancelTransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.cancel.registry.CancelScenarioRegistry;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelRequestMessage;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelResponseMessage;
import com.chaeyeongmin.van_sim.transaction.cancel.CancelService;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
import com.chaeyeongmin.van_sim.transaction.cancel.tcp.exception.CancelTcpMessageException;
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

@Component
@Profile("postgres")
@RequiredArgsConstructor
public class CancelTcpHandler {

    private static final String PROTOCOL_VERSION = "1";
    private static final String MESSAGE_TYPE = "CANCEL";

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
    private final CancelTcpMessageMapper mapper;
    private final CancelService cancelService;
    private final CancelScenarioRegistry registry;

    public byte[] handle(byte[] payload) {
        // TCP 서버가 수신한 원본 JSON 바이트 payload를 취소 요청 전문 객체로 역직렬화한다.
        CancelRequestMessage cancelRequest = readCancelRequest(payload);

        // 취소 요청 전문 객체 값 체크
        validate(cancelRequest);

        // 취소 요청 전문에 담긴 거래 정보를 서비스 계층이 처리할 수 있는 커맨드 모델로 변환한다.
        CancelCommand cancelCommand = mapper.toCommand(cancelRequest);

        // 취소 서비스에 커맨드를 전달해 취소 가능 여부와 응답에 필요한 처리 결과를 계산한다.
        // 이 호출이 반환된 시점에는 CancelService @Transactional 경계가 끝나 원장 저장도 commit된 뒤다.
        CancelResult cancelResult = cancelService.processCancel(cancelCommand);

        // DROP_RESPONSE는 TCP 응답만 유실시키는 transport 계층 시나리오다.
        // 따라서 서비스 트랜잭션 안에 넣지 않고, 업무 처리 완료 후 응답 payload를 만들기 전에 적용한다.
        if (shouldDropResponse(cancelRequest)) return null;

        // 원 요청 전문의 식별 정보와 서비스 처리 결과를 조합해 TCP 응답 전문 객체를 만든다.
        CancelResponseMessage cancelResponse = mapper.toResponse(cancelRequest, cancelResult);

        // 응답 전문 객체를 TCP 클라이언트로 되돌려 보낼 JSON 바이트 payload로 직렬화한다.
        return writeCancelResponse(cancelResponse);
    }

    /**
     * 취소 요청 전문의 최소 프로토콜 계약을 검증한다.
     *
     * <p>
     * TCP boundary에서 잘못된 전문이 CancelService와 VAN 원장 처리까지
     * 내려가지 않도록 서비스 호출 전에 검증한다.
     */
    private void validate(CancelRequestMessage request) {
        if (PROTOCOL_VERSION.equals(request.protocolVersion()) == false
                || MESSAGE_TYPE.equals(request.messageType()) == false
                || isBlank(request.requestId())
                || isInvalidPosTrx(request.cancelPosTrx())
                || isInvalidPosTrx(request.originalPosTrx())
                || request.originalAttemptSeq() <= 0
                || isBlank(request.originalVanTrxId())
                || isBlank(request.originalApprovalNo())
                || request.amount() <= 0) {
            throw new CancelTcpMessageException("CANCEL_TCP_REQUEST_INVALID");
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 수신 payload를 취소 요청 전문으로 변환한다.
     */
    private CancelRequestMessage readCancelRequest(byte[] payload) {
        try {
            return objectMapper.readValue(payload, CancelRequestMessage.class);
        } catch (IOException e) {
            throw new CancelTcpMessageException(
                    "CANCEL_TCP_REQUEST_DESERIALIZE_FAILED",
                    e
            );
        }

    }

    /**
     * 취소 응답 전문을 송신 payload로 변환한다.
     */
    private byte[] writeCancelResponse(CancelResponseMessage response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (IOException e) {
            throw new CancelTcpMessageException(
                    "CANCEL_TCP_RESPONSE_SERIALIZE_FAILED",
                    e
            );
        }

    }

    /**
     * 해당 시나리오가 DROP_RESPONSE 시나리오인지 검증한다.
     */
    private boolean shouldDropResponse(CancelRequestMessage request) {
        return registry.find(request.cancelPosTrx())
                .map(scenario -> scenario.transportBehavior() == CancelTransportBehavior.DROP_RESPONSE)
                .orElse(false);
    }

}
