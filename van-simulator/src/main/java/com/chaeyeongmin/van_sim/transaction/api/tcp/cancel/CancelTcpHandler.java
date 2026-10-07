package com.chaeyeongmin.van_sim.transaction.api.tcp.cancel;

import com.chaeyeongmin.van_sim.protocol.cancel.CancelRequestMessage;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelResponseMessage;
import com.chaeyeongmin.van_sim.scenario.application.cancel.CancelScenarioRegistry;
import com.chaeyeongmin.van_sim.scenario.domain.cancel.CancelTransportBehavior;
import com.chaeyeongmin.van_sim.transaction.api.tcp.cancel.exception.CancelTcpMessageException;
import com.chaeyeongmin.van_sim.transaction.api.tcp.support.PosTrxProtocolValidator;
import com.chaeyeongmin.van_sim.transaction.application.cancel.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.application.cancel.result.CancelResult;
import com.chaeyeongmin.van_sim.transaction.application.cancel.service.CancelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 취소 TCP 요청 전문을 CancelService에 연결하는 진입 핸들러다.
 *
 * <p>
 * JSON payload 역직렬화, 프로토콜 검증, 서비스 호출, 응답 전문 직렬화를 담당한다.
 * DROP_RESPONSE 시나리오는 원장 처리가 commit된 뒤 TCP 응답만 유실시키도록 적용한다.
 */
@Component
@Profile("postgres")
@RequiredArgsConstructor
@Slf4j
public class CancelTcpHandler {

    private static final String PROTOCOL_VERSION = "1";
    private static final String MESSAGE_TYPE = "CANCEL";

    private final ObjectMapper objectMapper;
    private final CancelTcpMessageMapper mapper;
    private final CancelService service;
    private final CancelScenarioRegistry registry;
    private final PosTrxProtocolValidator posTrxProtocolValidator;

    /** 취소 TCP 요청 payload를 처리하고 응답 payload를 반환한다. */
    public byte[] handle(byte[] payload) {
        // TCP 서버가 수신한 원본 JSON 바이트 payload를 취소 요청 전문 객체로 역직렬화한다.
        CancelRequestMessage cancelRequest = readCancelRequest(payload);
        log.info("[van-tcp][cancel][received] requestId={}, cancelPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                cancelRequest.requestId(), cancelRequest.cancelPosTrx(), cancelRequest.originalPosTrx(), cancelRequest.originalAttemptSeq());

        // 취소 요청 전문의 최소 프로토콜 계약을 검증한다.
        validate(cancelRequest);

        // 취소 요청 전문에 담긴 거래 정보를 서비스 계층이 처리할 수 있는 커맨드 모델로 변환한다.
        CancelCommand cancelCommand = mapper.toCommand(cancelRequest);

        // 취소 서비스에 커맨드를 전달해 취소 가능 여부와 응답에 필요한 처리 결과를 계산한다.
        // 이 호출이 반환된 시점에는 CancelService @Transactional 경계가 끝나 원장 저장도 commit된 뒤다.
        CancelResult cancelResult = service.processCancel(cancelCommand);
        log.info("[van-tcp][cancel][result] requestId={}, cancelPosTrx={}, originalPosTrx={}, originalAttemptSeq={}, status={}, resultCode={}, vanTrxId={}, approvalNo={}, declineCode={}",
                cancelRequest.requestId(), cancelResult.cancelPosTrx(), cancelResult.originalPosTrx(), cancelResult.originalAttemptSeq(),
                cancelResult.cancelStatus(), cancelResult.resultCode(), cancelResult.vanCancelTrxId(),
                cancelResult.cancelApprovalNo(), cancelResult.declineCode());

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
                || posTrxProtocolValidator.isInvalid(request.cancelPosTrx())
                || posTrxProtocolValidator.isInvalid(request.originalPosTrx())
                || request.originalAttemptSeq() <= 0
                || isBlank(request.originalVanTrxId())
                || isBlank(request.originalApprovalNo())
                || request.amount() <= 0) {
            throw new CancelTcpMessageException("CANCEL_TCP_REQUEST_INVALID");
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
