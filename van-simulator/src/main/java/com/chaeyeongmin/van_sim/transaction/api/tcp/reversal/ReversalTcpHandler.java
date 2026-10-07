package com.chaeyeongmin.van_sim.transaction.api.tcp.reversal;

import com.chaeyeongmin.van_sim.protocol.reversal.ReversalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.reversal.ReversalResponseMessage;
import com.chaeyeongmin.van_sim.transaction.api.tcp.reversal.exception.ReversalTcpMessageException;
import com.chaeyeongmin.van_sim.transaction.api.tcp.support.PosTrxProtocolValidator;
import com.chaeyeongmin.van_sim.transaction.application.reversal.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.application.reversal.result.ReversalResult;
import com.chaeyeongmin.van_sim.transaction.application.reversal.service.ReversalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 망취소 TCP 요청 전문을 ReversalService에 연결하는 진입 핸들러다.
 *
 * <p>
 * JSON payload 역직렬화, 프로토콜 검증, 서비스 호출, 응답 전문 직렬화를 담당한다.
 * 원장 처리 transaction이 끝난 뒤 결과를 TCP 응답 전문으로 변환한다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
@Slf4j
public class ReversalTcpHandler {

    private static final String PROTOCOL_VERSION = "1";
    private static final String MESSAGE_TYPE = "REVERSAL";

    private final ObjectMapper objectMapper;
    private final ReversalTcpMessageMapper mapper;
    private final ReversalService service;
    private final PosTrxProtocolValidator posTrxProtocolValidator;

    /** 망취소 TCP 요청 payload를 처리하고 응답 payload를 반환한다. */
    public byte[] handle(byte[] payload) {
        // TCP 서버가 수신한 원본 JSON 바이트 payload를 reversal 요청 전문 객체로 역직렬화한다.
        ReversalRequestMessage reversalRequest = readReversalRequest(payload);
        log.info("[van-tcp][reversal][received] requestId={}, reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                reversalRequest.requestId(), reversalRequest.reversalPosTrx(), reversalRequest.originalPosTrx(), reversalRequest.originalAttemptSeq());

        // 망취소 요청 전문의 최소 프로토콜 계약을 검증한다.
        validate(reversalRequest);

        // reversal 요청 전문에 담긴 거래 정보를 서비스 계층이 처리할 수 있는 커맨드 모델로 변환한다.
        ReversalCommand reversalCommand = mapper.toCommand(reversalRequest);

        // reversal 서비스에 커맨드를 전달해 원승인 row 기준 reversal 가능 여부와 응답 결과를 계산한다.
        // 이 호출이 반환된 시점에는 ReversalService @Transactional 경계가 끝나 원장 저장도 commit된 뒤다.
        ReversalResult reversalResult = service.processReversal(reversalCommand);
        log.info("[van-tcp][reversal][result] requestId={}, reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}, status={}, resultCode={}, vanTrxId={}, approvalNo={}, declineCode={}",
                reversalRequest.requestId(), reversalResult.reversalPosTrx(), reversalResult.originalPosTrx(),
                reversalResult.originalAttemptSeq(), reversalResult.reversalStatus(), reversalResult.resultCode(),
                reversalResult.vanReversalTrxId(), reversalResult.reversalApprovalNo(), reversalResult.declineCode());

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
                || posTrxProtocolValidator.isInvalid(request.reversalPosTrx())
                || posTrxProtocolValidator.isInvalid(request.originalPosTrx())
                || request.originalAttemptSeq() <= 0
                || request.amount() <= 0) {
            throw new ReversalTcpMessageException("REVERSAL_TCP_REQUEST_INVALID");
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
