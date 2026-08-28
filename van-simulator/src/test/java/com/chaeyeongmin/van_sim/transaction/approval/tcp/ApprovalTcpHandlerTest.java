package com.chaeyeongmin.van_sim.transaction.approval.tcp;

import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.ApprovalService;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.approval.tcp.exception.ApprovalTcpMessageException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalTcpHandlerTest {

    @Mock
    ApprovalService approvalService;

    @Mock
    ApprovalScenarioRegistry scenarioRegistry;

    ApprovalTcpMessageMapper mapper;
    ObjectMapper objectMapper;
    ApprovalTcpHandler handler;

    @BeforeEach
    void setUp() {
        mapper = new ApprovalTcpMessageMapper();

        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        handler = new ApprovalTcpHandler(
                objectMapper,
                mapper,
                approvalService,
                scenarioRegistry
        );
    }

    @Test
    void 승인_TCP_payload를_처리하고_APPROVED_응답_payload를_반환한다() throws IOException {
        // given
        ApprovalRequestMessage request = validRequest();

        ApprovalResult result = new ApprovalResult(
                "VAN-TEST-001",
                request.posTrx(),
                request.attemptSeq(),
                VanApprovalStatus.APPROVED,
                "APPROVAL-TEST-001",
                null,
                LocalDateTime.of(2026, 8, 13, 17, 30)
        );

        when(approvalService.processApproval(any(ApprovalCommand.class)))
                .thenReturn(result);
        when(scenarioRegistry.find(request.posTrx()))
                .thenReturn(Optional.empty());

        byte[] payload = objectMapper.writeValueAsBytes(request);

        // when
        byte[] responsePayload = handler.handle(payload);

        // then
        ApprovalResponseMessage response =
                objectMapper.readValue(responsePayload, ApprovalResponseMessage.class);

        assertThat(response.status()).isEqualTo(ApprovalResponseStatus.APPROVED);
        assertThat(response.requestId()).isEqualTo("REQ-001");
        assertThat(response.vanTrxId()).isEqualTo("VAN-TEST-001");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void 승인_TCP_요청_프로토콜이_유효하지_않으면_서비스를_호출하지_않는다(
            String caseName,
            ApprovalRequestMessage request
    ) throws IOException {
        // given
        byte[] payload = objectMapper.writeValueAsBytes(request);

        // when + then
        ApprovalTcpMessageException exception =
                assertThrows(ApprovalTcpMessageException.class, () -> handler.handle(payload));

        assertThat(exception.getMessage()).isEqualTo("APPROVAL_TCP_REQUEST_INVALID");
        verifyNoInteractions(approvalService, scenarioRegistry);
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("unsupported protocolVersion", requestWith("2", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, "1234567812345678", "2812")),
                Arguments.of("invalid messageType", requestWith("1", "INQUIRY", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, "1234567812345678", "2812")),
                Arguments.of("blank requestId", requestWith("1", "APPROVAL", "  ", "2301-20260808-9999-0001", 1, 10_000, "1234567812345678", "2812")),
                Arguments.of("blank posTrx", requestWith("1", "APPROVAL", "REQ-001", "  ", 1, 10_000, "1234567812345678", "2812")),
                Arguments.of("invalid posTrx format", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-1", 1, 10_000, "1234567812345678", "2812")),
                Arguments.of("invalid posTrx date", requestWith("1", "APPROVAL", "REQ-001", "2301-20260230-9999-0001", 1, 10_000, "1234567812345678", "2812")),
                Arguments.of("zero attemptSeq", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 0, 10_000, "1234567812345678", "2812")),
                Arguments.of("negative amount", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, -1, "1234567812345678", "2812")),
                Arguments.of("null pan", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, null, "2812")),
                Arguments.of("short pan", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, "12345678", "2812")),
                Arguments.of("non-digit pan", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, "123456781234567X", "2812")),
                Arguments.of("invalid expiry length", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, "1234567812345678", "281")),
                Arguments.of("invalid expiry month", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, "1234567812345678", "2813")),
                Arguments.of("non-digit expiry", requestWith("1", "APPROVAL", "REQ-001", "2301-20260808-9999-0001", 1, 10_000, "1234567812345678", "28A2"))
        );
    }

    private static ApprovalRequestMessage validRequest() {
        return requestWith(
                "1",
                "APPROVAL",
                "REQ-001",
                "2301-20260808-9999-0001",
                1,
                10_000,
                "1234567812345678",
                "2812"
        );
    }

    private static ApprovalRequestMessage requestWith(
            String protocolVersion,
            String messageType,
            String requestId,
            String posTrx,
            int attemptSeq,
            int amount,
            String pan,
            String expiryYyMm
    ) {
        return new ApprovalRequestMessage(
                protocolVersion,
                messageType,
                requestId,
                posTrx,
                attemptSeq,
                amount,
                pan,
                expiryYyMm
        );
    }
}
