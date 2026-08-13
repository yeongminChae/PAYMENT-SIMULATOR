package com.chaeyeongmin.van_sim.transaction.approval.tcp;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.ApprovalService;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalTcpHandlerTest {

    @Mock
    ApprovalService approvalService;

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
                approvalService
        );
    }

    @Test
    void 승인_TCP_payload를_처리하고_APPROVED_응답_payload를_반환한다() throws IOException {
        // given
        ApprovalRequestMessage request = new ApprovalRequestMessage(
                "1",
                "APPROVAL",
                "REQ-001",
                "2301-20260808-9999-0001",
                1,
                10_000,
                "1234567812345678",
                "2812"
        );

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

}