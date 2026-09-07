package com.chaeyeongmin.van_sim.transaction.reversal.tcp;

import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.protocol.reversal.ReversalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.reversal.ReversalResponseMessage;
import com.chaeyeongmin.van_sim.transaction.reversal.ReversalService;
import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;
import com.chaeyeongmin.van_sim.transaction.reversal.tcp.exception.ReversalTcpMessageException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReversalTcpHandlerTest {

    @Mock
    private ReversalService reversalService;

    private ObjectMapper objectMapper;
    private ReversalTcpHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        handler = new ReversalTcpHandler(
                objectMapper,
                new ReversalTcpMessageMapper(),
                reversalService
        );
    }

    @Test
    void 정상_REVERSAL_payload를_처리하고_REVERSAL_RESPONSE_payload를_반환한다() throws Exception {
        ReversalRequestMessage request = validRequest();
        ReversalResult result = successResult(request);
        byte[] payload = objectMapper.writeValueAsBytes(request);

        when(reversalService.processReversal(any(ReversalCommand.class))).thenReturn(result);

        byte[] responsePayload = handler.handle(payload);

        ArgumentCaptor<ReversalCommand> captor = ArgumentCaptor.forClass(ReversalCommand.class);
        verify(reversalService, times(1)).processReversal(captor.capture());

        ReversalCommand command = captor.getValue();
        assertThat(command.reversalPosTrx()).isEqualTo(request.reversalPosTrx());
        assertThat(command.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(command.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(command.amount()).isEqualTo(request.amount());

        ReversalResponseMessage response = objectMapper.readValue(responsePayload, ReversalResponseMessage.class);
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("REVERSAL_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.reversalPosTrx()).isEqualTo(result.reversalPosTrx());
        assertThat(response.originalPosTrx()).isEqualTo(result.originalPosTrx());
        assertThat(response.originalAttemptSeq()).isEqualTo(result.originalAttemptSeq());
        assertThat(response.vanReversalTrxId()).isEqualTo(result.vanReversalTrxId());
        assertThat(response.reversalStatus()).isEqualTo(result.reversalStatus());
        assertThat(response.resultCode()).isEqualTo(result.resultCode());
        assertThat(response.reversalApprovalNo()).isEqualTo(result.reversalApprovalNo());
        assertThat(response.declineCode()).isNull();

        JsonNode responseJson = objectMapper.readTree(responsePayload);
        assertThat(responseJson.has("amount")).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequiredFields")
    void 필수값이_invalid이면_ReversalTcpMessageException을_던지고_service를_호출하지_않는다(
            String caseName,
            ReversalRequestMessage request
    ) throws Exception {
        byte[] payload = objectMapper.writeValueAsBytes(request);

        assertThatThrownBy(() -> handler.handle(payload))
                .isInstanceOf(ReversalTcpMessageException.class)
                .hasMessage("REVERSAL_TCP_REQUEST_INVALID");
        verifyNoInteractions(reversalService);
    }

    @Test
    void malformed_JSON이면_역직렬화_실패_예외를_던지고_service를_호출하지_않는다() {
        byte[] payload = """
                {"protocolVersion":"1","messageType":"REVERSAL",
                """.getBytes();

        assertThatThrownBy(() -> handler.handle(payload))
                .isInstanceOf(ReversalTcpMessageException.class)
                .hasMessage("REVERSAL_TCP_REQUEST_DESERIALIZE_FAILED");
        verifyNoInteractions(reversalService);
    }

    private static Stream<Arguments> invalidRequiredFields() {
        return Stream.of(
                Arguments.of("invalid protocolVersion", requestWith("2", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, 10_000)),
                Arguments.of("invalid messageType", requestWith("1", "CANCEL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, 10_000)),
                Arguments.of("blank requestId", requestWith("1", "REVERSAL", "  ", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, 10_000)),
                Arguments.of("blank reversalPosTrx", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "  ", "2301-20260808-9999-0001", 1, 10_000)),
                Arguments.of("invalid reversalPosTrx", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-1", "2301-20260808-9999-0001", 1, 10_000)),
                Arguments.of("invalid reversalPosTrx date", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260230-9999-0002", "2301-20260808-9999-0001", 1, 10_000)),
                Arguments.of("blank originalPosTrx", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "  ", 1, 10_000)),
                Arguments.of("invalid originalPosTrx", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260808-9999-1", 1, 10_000)),
                Arguments.of("invalid originalPosTrx date", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260230-9999-0001", 1, 10_000)),
                Arguments.of("zero originalAttemptSeq", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 0, 10_000)),
                Arguments.of("negative originalAttemptSeq", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", -1, 10_000)),
                Arguments.of("zero amount", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, 0)),
                Arguments.of("negative amount", requestWith("1", "REVERSAL", "REQ-REVERSAL-001", "2301-20260808-9999-0002", "2301-20260808-9999-0001", 1, -1))
        );
    }

    private static ReversalRequestMessage validRequest() {
        return requestWith(
                "1",
                "REVERSAL",
                "REQ-REVERSAL-001",
                "2301-20260808-9999-0002",
                "2301-20260808-9999-0001",
                1,
                10_000
        );
    }

    private static ReversalRequestMessage requestWith(
            String protocolVersion,
            String messageType,
            String requestId,
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            int amount
    ) {
        return new ReversalRequestMessage(
                protocolVersion,
                messageType,
                requestId,
                reversalPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                amount
        );
    }

    private static ReversalResult successResult(ReversalRequestMessage request) {
        return new ReversalResult(
                "VAN-REVERSAL-001",
                request.reversalPosTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                request.amount(),
                VanReversalStatus.REVERSED,
                ReversalResultCode.SUCCESS,
                "REVERSAL-APPROVAL-001",
                null,
                LocalDateTime.of(2026, 9, 4, 10, 0)
        );
    }
}
