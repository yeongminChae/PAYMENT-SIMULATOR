package com.chaeyeongmin.van_sim.transaction.reversal.tcp;

import com.chaeyeongmin.van_sim.protocol.reversal.ReversalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.reversal.ReversalResponseMessage;
import com.chaeyeongmin.van_sim.transaction.reversal.ReversalService;
import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;
import com.chaeyeongmin.van_sim.transaction.reversal.tcp.exception.ReversalTcpMessageException;
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
public class ReversalTcpHandler {

    private static final String PROTOCOL_VERSION = "1";
    private static final String MESSAGE_TYPE = "REVERSAL";

    /**
     * Payment Server가 발급하는 POS 거래번호 형식이다.
     * storeCd(4)-bizDate(8)-posNo(4)-seq(4) 구조이며, reversalPosTrx와 originalPosTrx에 동일하게 적용한다.
     */
    private static final Pattern POS_TRX_PATTERN = Pattern.compile("^\\d{4}-\\d{8}-\\d{4}-\\d{4}$");

    /**
     * posTrx 안의 bizDate가 20260230 같은 허위 날짜로 들어오는 것을 막기 위한 strict parser다.
     */
    private static final DateTimeFormatter BIZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private final ObjectMapper objectMapper;
    private final ReversalTcpMessageMapper mapper;
    private final ReversalService reversalService;

    public byte[] handle(byte[] payload) {
        // TCP 서버가 수신한 원본 JSON 바이트 payload를 reversal 요청 전문 객체로 역직렬화한다.
        ReversalRequestMessage reversalRequest = readReversalRequest(payload);

        // reversal 요청 전문 객체 값 체크
        validate(reversalRequest);

        // reversal 요청 전문에 담긴 거래 정보를 서비스 계층이 처리할 수 있는 커맨드 모델로 변환한다.
        ReversalCommand reversalCommand = mapper.toCommand(reversalRequest);

        // reversal 서비스에 커맨드를 전달해 원승인 row 기준 reversal 가능 여부와 응답 결과를 계산한다.
        // 이 호출이 반환된 시점에는 ReversalService @Transactional 경계가 끝나 원장 저장도 commit된 뒤다.
        ReversalResult reversalResult = reversalService.processReversal(reversalCommand);

        // 원 요청 전문의 식별 정보와 서비스 처리 결과를 조합해 TCP 응답 전문 객체를 만든다.
        // Reversal 응답 전문 계약에는 amount를 포함하지 않는다.
        ReversalResponseMessage reversalResponse = mapper.toResponse(reversalRequest, reversalResult);

        // 응답 전문 객체를 TCP 클라이언트로 되돌려 보낼 JSON 바이트 payload로 직렬화한다.
        return writeReversalResponse(reversalResponse);
    }

    /**
     * reversal 요청 전문의 최소 프로토콜 계약을 검증한다.
     *
     * <p>
     * TCP boundary에서 잘못된 전문이 ReversalService와 VAN 원장 처리까지
     * 내려가지 않도록 서비스 호출 전에 검증한다.
     */
    private void validate(ReversalRequestMessage request) {
        if (PROTOCOL_VERSION.equals(request.protocolVersion()) == false
                || MESSAGE_TYPE.equals(request.messageType()) == false
                || isBlank(request.requestId())
                || isInvalidPosTrx(request.reversalPosTrx())
                || isInvalidPosTrx(request.originalPosTrx())
                || request.originalAttemptSeq() <= 0
                || request.amount() <= 0) {
            throw new ReversalTcpMessageException("REVERSAL_TCP_REQUEST_INVALID");
        }
    }

    /**
     * posTrx는 VAN reversal 원장의 멱등 key와 원승인 조회 key로 사용된다.
     * 형식이 깨진 값이 원장까지 내려가면 이후 재응답과 원승인 correlation 기준도 함께 흔들리므로
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
     * 수신 payload를 reversal 요청 전문으로 변환한다.
     */
    private ReversalRequestMessage readReversalRequest(byte[] payload) {
        try {
            return objectMapper.readValue(payload, ReversalRequestMessage.class);
        } catch (IOException e) {
            throw new ReversalTcpMessageException(
                    "REVERSAL_TCP_REQUEST_DESERIALIZE_FAILED",
                    e
            );
        }
    }

    /**
     * reversal 응답 전문을 송신 payload로 변환한다.
     */
    private byte[] writeReversalResponse(ReversalResponseMessage response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (IOException e) {
            throw new ReversalTcpMessageException(
                    "REVERSAL_TCP_RESPONSE_SERIALIZE_FAILED",
                    e
            );
        }
    }
}
