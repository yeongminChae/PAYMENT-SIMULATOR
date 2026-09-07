package com.chaeyeongmin.van_sim.transaction.inquiry.tcp;

import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResultCode;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseStatus;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryTargetType;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.InquiryService;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.tcp.exception.InquiryTcpMessageException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InquiryTcpHandlerTest {

    private static final String POS_TRX = "2301-20260808-9999-0001";
    private static final int ATTEMPT_SEQ = 1;

    @Mock
    private InquiryService inquiryService;

    private ObjectMapper objectMapper;
    private InquiryTcpHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        handler = new InquiryTcpHandler(
                objectMapper,
                new InquiryTcpMessageMapper(),
                inquiryService
        );
    }

    @Test
    void APPROVED_InquiryResult를_INQUIRY_RESPONSE로_반환한다() throws IOException {
        // given
        InquiryRequestMessage request = newRequest("REQ-INQUIRY-001");
        ApprovalInquiryResult result = new ApprovalInquiryResult(
                "VAN-TEST-001",
                POS_TRX,
                ATTEMPT_SEQ,
                VanApprovalStatus.APPROVED,
                "APPROVAL-TEST-001",
                null,
                LocalDateTime.of(2026, 8, 27, 10, 0)
        );
        when(inquiryService.inquireApproval(POS_TRX, ATTEMPT_SEQ)).thenReturn(Optional.of(result));

        // when
        byte[] responsePayload = handler.handle(objectMapper.writeValueAsBytes(request));

        // then
        InquiryResponseMessage response =
                objectMapper.readValue(responsePayload, InquiryResponseMessage.class);
        assertResponseIdentity(response, request);
        assertThat(response.status()).isEqualTo(InquiryResponseStatus.APPROVED);
        assertThat(response.resultCode()).isEqualTo(InquiryResultCode.SUCCESS);
        assertThat(response.approvalNo()).isEqualTo("APPROVAL-TEST-001");
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.vanTrxId()).isEqualTo("VAN-TEST-001");
        assertThat(response.declineCode()).isNull();
        verify(inquiryService).inquireApproval(POS_TRX, ATTEMPT_SEQ);
    }

    @Test
    void 원장이_없는_UNKNOWN_InquiryResult를_null_원장값과_함께_반환한다() throws IOException {
        // given
        InquiryRequestMessage request = newRequest("REQ-INQUIRY-002");
        ApprovalInquiryResult result = new ApprovalInquiryResult(
                null,
                POS_TRX,
                ATTEMPT_SEQ,
                VanApprovalStatus.UNKNOWN,
                null,
                null,
                null
        );
        when(inquiryService.inquireApproval(POS_TRX, ATTEMPT_SEQ)).thenReturn(Optional.of(result));

        // when
        byte[] responsePayload = handler.handle(objectMapper.writeValueAsBytes(request));

        // then
        InquiryResponseMessage response =
                objectMapper.readValue(responsePayload, InquiryResponseMessage.class);
        assertResponseIdentity(response, request);
        assertThat(response.status()).isEqualTo(InquiryResponseStatus.UNKNOWN);
        assertThat(response.resultCode()).isEqualTo(InquiryResultCode.SUCCESS);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
        assertThat(response.vanTrxId()).isNull();
        verify(inquiryService).inquireApproval(POS_TRX, ATTEMPT_SEQ);
    }

    @Test
    void APPROVAL_조회에서_targetAttemptSeq가_null이면_요청_오류로_거절한다() throws IOException {
        InquiryRequestMessage request = new InquiryRequestMessage(
                "1",
                "INQUIRY",
                "REQ-INQUIRY-INVALID-001",
                InquiryTargetType.APPROVAL,
                POS_TRX,
                null
        );

        assertThatThrownBy(() -> handler.handle(objectMapper.writeValueAsBytes(request)))
                .isInstanceOf(InquiryTcpMessageException.class)
                .hasMessage("INQUIRY_TCP_REQUEST_INVALID");
    }

    @Test
    void CANCEL_조회에서_targetAttemptSeq가_존재하면_요청_오류로_거절한다() throws IOException {
        InquiryRequestMessage request = new InquiryRequestMessage(
                "1",
                "INQUIRY",
                "REQ-INQUIRY-INVALID-002",
                InquiryTargetType.CANCEL,
                POS_TRX,
                ATTEMPT_SEQ
        );

        assertThatThrownBy(() -> handler.handle(objectMapper.writeValueAsBytes(request)))
                .isInstanceOf(InquiryTcpMessageException.class)
                .hasMessage("INQUIRY_TCP_REQUEST_INVALID");
    }

    private static InquiryRequestMessage newRequest(String requestId) {
        return new InquiryRequestMessage(
                "1",
                "INQUIRY",
                requestId,
                InquiryTargetType.APPROVAL,
                POS_TRX,
                ATTEMPT_SEQ
        );
    }

    private static void assertResponseIdentity(
            InquiryResponseMessage response,
            InquiryRequestMessage request
    ) {
        assertThat(response.protocolVersion()).isEqualTo("1");
        assertThat(response.messageType()).isEqualTo("INQUIRY_RESPONSE");
        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.targetType()).isEqualTo(request.targetType());
        assertThat(response.targetTrxNo()).isEqualTo(request.targetTrxNo());
        assertThat(response.targetAttemptSeq()).isEqualTo(request.targetAttemptSeq());
        assertThat(response.respondedAt()).isNotNull();
    }
}
