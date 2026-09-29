package com.chaeyeongmin.payment_sim.van.gateway.impl.tcp;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpResponse;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TcpInquiryOperationTest {

    @Mock
    private VanTcpClient vanTcpClient;

    private ObjectMapper objectMapper;
    private TcpInquiryOperation inquiryOperation;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        inquiryOperation = new TcpInquiryOperation(
                objectMapper,
                vanTcpClient
        );
    }

    @Test
    void 기존_조회요청을_TCP_조회전문으로_변환하고_APPROVED_응답을_업무응답으로_변환한다() throws Exception {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0101", 1);
        LocalDateTime respondedAt = LocalDateTime.of(2026, 8, 24, 11, 30);
        VanInquiryTcpResponse tcpResponse = inquiryTcpResponse(
                request,
                VanInquiryStatus.APPROVED,
                "VAN-INQ-001",
                "APPROVAL-INQ-001",
                null,
                respondedAt
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        VanInquiryResponse response = inquiryOperation.execute(request);

        // then
        ArgumentCaptor<byte[]> requestPayloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(vanTcpClient).send(requestPayloadCaptor.capture());

        VanInquiryTcpRequest tcpRequest =
                objectMapper.readValue(requestPayloadCaptor.getValue(), VanInquiryTcpRequest.class);

        assertThat(tcpRequest.protocolVersion()).isEqualTo("1");
        assertThat(tcpRequest.messageType()).isEqualTo("INQUIRY");
        assertThat(tcpRequest.requestId()).isEqualTo("INQUIRY-APPROVAL-2301-20260808-9999-0101-1");
        assertThat(tcpRequest.targetType()).isEqualTo(VanInquiryTargetType.APPROVAL);
        assertThat(tcpRequest.targetTrxNo()).isEqualTo(request.targetTrxNo());
        assertThat(tcpRequest.targetAttemptSeq()).isEqualTo(request.targetAttemptSeq());

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.APPROVAL);
        assertThat(response.targetTrxNo()).isEqualTo(request.targetTrxNo());
        assertThat(response.targetAttemptSeq()).isEqualTo(request.targetAttemptSeq());
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.APPROVED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQ-001");
        assertThat(response.approvalNo()).isEqualTo("APPROVAL-INQ-001");
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
        assertThat(response.respondedAt()).isEqualTo(respondedAt);
    }

    @Test
    void TCP_DECLINED_조회응답을_DECLINED_업무응답으로_변환한다() throws Exception {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0102", 1);
        VanInquiryTcpResponse tcpResponse = inquiryTcpResponse(
                request,
                VanInquiryStatus.DECLINED,
                "VAN-INQ-002",
                null,
                "05",
                LocalDateTime.of(2026, 8, 24, 11, 31)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        VanInquiryResponse response = inquiryOperation.execute(request);

        // then
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.DECLINED);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.DO_NOT_HONOR);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQ-002");
    }

    @Test
    void TCP_UNKNOWN_조회응답을_UNKNOWN_업무응답으로_변환하고_TIMEOUT_declineCode를_반환한다() throws Exception {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0103", 1);
        VanInquiryTcpResponse tcpResponse = inquiryTcpResponse(
                request,
                VanInquiryStatus.UNKNOWN,
                "VAN-INQ-003",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 32)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        VanInquiryResponse response = inquiryOperation.execute(request);

        // then
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.UNKNOWN);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.TIMEOUT);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQ-003");
    }

    @Test
    void TCP_APPROVAL_NOT_FOUND_조회응답을_NOT_FOUND_업무응답으로_변환한다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0106", 1);
        VanInquiryTcpResponse tcpResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-APPROVAL-" + request.targetTrxNo() + "-" + request.targetAttemptSeq(),
                VanInquiryTargetType.APPROVAL,
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                VanInquiryResultCode.NOT_FOUND,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 34)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = inquiryOperation.execute(request);

        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.NOT_FOUND);
        assertThat(response.status()).isNull();
        assertThat(response.vanTrxId()).isNull();
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
    }

    @Test
    void TCP_CANCEL_CANCELLED_조회응답은_역직렬화와_검증을_통과한다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260808-9999-0107");
        VanInquiryTcpResponse tcpResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-CANCEL-" + request.targetTrxNo() + "-null",
                VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                null,
                VanInquiryResultCode.SUCCESS,
                "VAN-CANCEL-INQ-001",
                VanInquiryStatus.CANCELLED,
                null,
                "CANCEL-APPROVAL-INQ-001",
                null,
                LocalDateTime.of(2026, 8, 24, 11, 35)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = inquiryOperation.execute(request);

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.CANCEL);
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.CANCELLED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-CANCEL-INQ-001");
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isEqualTo("CANCEL-APPROVAL-INQ-001");
    }

    @Test
    void TCP_CANCEL_ALREADY_REVERSED_조회응답의_declineCode를_보존한다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260928-9999-0101");
        VanInquiryTcpResponse tcpResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                inquiryRequestId(request),
                VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                null,
                VanInquiryResultCode.SUCCESS,
                "VAN-CANCEL-INQ-ALREADY-REVERSED",
                VanInquiryStatus.CANCEL_DECLINED,
                null,
                null,
                "ALREADY_REVERSED",
                LocalDateTime.of(2026, 9, 28, 10, 0)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = inquiryOperation.execute(request);

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.CANCEL);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.CANCEL_DECLINED);
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.ALREADY_REVERSED);
    }

    @Test
    void TCP_CANCEL_ORIGINAL_NOT_APPROVED_조회응답의_declineCode를_보존한다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260928-9999-0102");
        VanInquiryTcpResponse tcpResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                inquiryRequestId(request),
                VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                null,
                VanInquiryResultCode.SUCCESS,
                "VAN-CANCEL-INQ-ORIGINAL-NOT-APPROVED",
                VanInquiryStatus.CANCEL_DECLINED,
                null,
                null,
                "ORIGINAL_NOT_APPROVED",
                LocalDateTime.of(2026, 9, 28, 10, 1)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = inquiryOperation.execute(request);

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.CANCEL);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.CANCEL_DECLINED);
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.ORIGINAL_NOT_APPROVED);
    }

    @Test
    void TCP_조회응답의_correlation이_다르면_예외를_던진다() throws Exception {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0104", 1);
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-2301-20260808-9999-0104-1",
                VanInquiryTargetType.APPROVAL,
                request.targetTrxNo(),
                2,
                VanInquiryResultCode.SUCCESS,
                "VAN-INQ-004",
                VanInquiryStatus.APPROVED,
                "APPROVAL-INQ-004",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 33)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        // when & then
        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_CANCEL_조회응답이_UNKNOWN_status이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260808-9999-0108");
        VanInquiryTcpResponse invalidResponse = inquiryTcpResponse(
                request,
                VanInquiryStatus.UNKNOWN,
                "VAN-CANCEL-INQ-INVALID-001",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 36)
        );

        when(vanTcpClient.send(any(byte[].class))) .thenReturn(objectMapper.writeValueAsBytes(invalidResponse));

        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_APPROVAL_조회응답이_CANCELLED_status이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0109", 1);
        VanInquiryTcpResponse invalidResponse = inquiryTcpResponse(
                request,
                VanInquiryStatus.CANCELLED,
                "VAN-INQ-INVALID-001",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 37)
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(invalidResponse));

        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_조회응답의_targetType_correlation이_다르면_예외를_던진다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0110", 1);
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-APPROVAL-" + request.targetTrxNo() + "-" + request.targetAttemptSeq(),
                VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                VanInquiryResultCode.SUCCESS,
                "VAN-INQ-005",
                VanInquiryStatus.CANCELLED,
                null,
                "CANCEL-APPROVAL-INQ-005",
                null,
                LocalDateTime.of(2026, 8, 24, 11, 38)
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_조회응답의_targetTrxNo_correlation이_다르면_예외를_던진다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0111", 1);
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-APPROVAL-" + request.targetTrxNo() + "-" + request.targetAttemptSeq(),
                VanInquiryTargetType.APPROVAL,
                "2301-20260808-9999-DIFF",
                request.targetAttemptSeq(),
                VanInquiryResultCode.SUCCESS,
                "VAN-INQ-006",
                VanInquiryStatus.APPROVED,
                "APPROVAL-INQ-006",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 39)
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_조회응답의_nullable_targetAttemptSeq_correlation이_다르면_예외를_던진다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260808-9999-0112");
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-CANCEL-" + request.targetTrxNo() + "-null",
                VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                1,
                VanInquiryResultCode.SUCCESS,
                "VAN-CANCEL-INQ-002",
                VanInquiryStatus.CANCELLED,
                null,
                "CANCEL-APPROVAL-INQ-002",
                null,
                LocalDateTime.of(2026, 8, 24, 11, 40)
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_REVERSAL_REVERSED_조회응답을_업무응답으로_변환한다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0401");
        VanInquiryTcpResponse tcpResponse = reversalInquiryTcpResponse(
                request,
                VanInquiryResultCode.SUCCESS,
                VanInquiryStatus.REVERSED,
                "VAN-REVERSAL-INQ-001",
                "REVERSAL-APPROVAL-INQ-001",
                null
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = inquiryOperation.execute(request);

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.REVERSAL);
        assertThat(response.targetTrxNo()).isEqualTo(request.targetTrxNo());
        assertThat(response.targetAttemptSeq()).isNull();
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.REVERSED);
        assertThat(response.reversalApprovalNo()).isEqualTo("REVERSAL-APPROVAL-INQ-001");
        assertThat(response.vanTrxId()).isEqualTo("VAN-REVERSAL-INQ-001");
        assertThat(response.declineCode()).isNull();
    }

    @Test
    void TCP_REVERSAL_DECLINED_조회응답의_VAN_decline_code_의미를_보존한다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0402");
        VanInquiryTcpResponse tcpResponse = reversalInquiryTcpResponse(
                request,
                VanInquiryResultCode.SUCCESS,
                VanInquiryStatus.REVERSAL_DECLINED,
                "VAN-REVERSAL-INQ-002",
                null,
                "ORIGINAL_NOT_REVERSIBLE"
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = inquiryOperation.execute(request);

        assertThat(response.status()).isEqualTo(VanInquiryStatus.REVERSAL_DECLINED);
        assertThat(response.reversalApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.ORIGINAL_NOT_REVERSIBLE);
    }

    @Test
    void TCP_REVERSAL_NOT_FOUND_조회응답을_빈_결과로_반환한다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0403");
        VanInquiryTcpResponse tcpResponse = reversalInquiryTcpResponse(
                request,
                VanInquiryResultCode.NOT_FOUND,
                null,
                null,
                null,
                null
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = inquiryOperation.execute(request);

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.REVERSAL);
        assertThat(response.targetTrxNo()).isEqualTo(request.targetTrxNo());
        assertThat(response.targetAttemptSeq()).isNull();
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.NOT_FOUND);
        assertThat(response.status()).isNull();
        assertThat(response.reversalApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
    }

    @Test
    void TCP_REVERSAL_조회응답의_targetType이_다르면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0404");
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1", "INQUIRY_RESPONSE", inquiryRequestId(request), VanInquiryTargetType.CANCEL,
                request.targetTrxNo(), null, VanInquiryResultCode.SUCCESS, "VAN-REVERSAL-INQ-004",
                VanInquiryStatus.REVERSED, null, null, "REVERSAL-APPROVAL-INQ-004", null,
                LocalDateTime.of(2026, 9, 15, 12, 4)
        );

        assertInquiryFailure(request, mismatchedResponse, "VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_REVERSAL_조회응답의_targetTrxNo가_다르면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0405");
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1", "INQUIRY_RESPONSE", inquiryRequestId(request), VanInquiryTargetType.REVERSAL,
                "2301-20260915-9999-DIFF", null, VanInquiryResultCode.SUCCESS, "VAN-REVERSAL-INQ-005",
                VanInquiryStatus.REVERSED, null, null, "REVERSAL-APPROVAL-INQ-005", null,
                LocalDateTime.of(2026, 9, 15, 12, 5)
        );

        assertInquiryFailure(request, mismatchedResponse, "VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_REVERSAL_조회응답의_targetAttemptSeq가_non_null이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0406");
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1", "INQUIRY_RESPONSE", inquiryRequestId(request), VanInquiryTargetType.REVERSAL,
                request.targetTrxNo(), 1, VanInquiryResultCode.SUCCESS, "VAN-REVERSAL-INQ-006",
                VanInquiryStatus.REVERSED, null, null, "REVERSAL-APPROVAL-INQ-006", null,
                LocalDateTime.of(2026, 9, 15, 12, 6)
        );

        assertInquiryFailure(request, mismatchedResponse, "VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_REVERSAL_조회응답이_APPROVED_status이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0407");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.APPROVED,
                "VAN-REVERSAL-INQ-007", null, null
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSAL_조회응답이_CANCELLED_status이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0408");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.CANCELLED,
                "VAN-REVERSAL-INQ-008", null, null
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSED_조회응답의_reversalApprovalNo가_null이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0409");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSED,
                "VAN-REVERSAL-INQ-009", null, null
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSED_조회응답의_reversalApprovalNo가_blank이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0410");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSED,
                "VAN-REVERSAL-INQ-010", " ", null
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSED_조회응답에_declineCode가_있으면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0411");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSED,
                "VAN-REVERSAL-INQ-011", "REVERSAL-APPROVAL-INQ-011", "ORIGINAL_NOT_REVERSIBLE"
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSAL_DECLINED_조회응답에_reversalApprovalNo가_있으면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0412");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSAL_DECLINED,
                "VAN-REVERSAL-INQ-012", "REVERSAL-APPROVAL-INQ-012", "ORIGINAL_NOT_REVERSIBLE"
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSAL_DECLINED_조회응답의_declineCode가_null이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0413");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSAL_DECLINED,
                "VAN-REVERSAL-INQ-013", null, null
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSAL_DECLINED_조회응답의_declineCode가_blank이면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0414");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSAL_DECLINED,
                "VAN-REVERSAL-INQ-014", null, " "
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_NOT_FOUND_조회응답에_reversalApprovalNo가_있으면_예외를_던진다() throws Exception {
        VanInquiryRequest request = reversalInquiryRequest("2301-20260915-9999-0415");
        VanInquiryTcpResponse invalidResponse = reversalInquiryTcpResponse(
                request, VanInquiryResultCode.NOT_FOUND, null,
                null, "REVERSAL-APPROVAL-INQ-015", null
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_APPROVAL_조회응답에_reversalApprovalNo가_있으면_예외를_던진다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260915-9999-0416", 1);
        VanInquiryTcpResponse invalidResponse = new VanInquiryTcpResponse(
                "1", "INQUIRY_RESPONSE", inquiryRequestId(request), VanInquiryTargetType.APPROVAL,
                request.targetTrxNo(), request.targetAttemptSeq(), VanInquiryResultCode.SUCCESS,
                "VAN-APPROVAL-INQ-016", VanInquiryStatus.APPROVED, "APPROVAL-INQ-016", null,
                "REVERSAL-APPROVAL-INQ-016", null, LocalDateTime.of(2026, 9, 15, 12, 16)
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_CANCEL_조회응답에_reversalApprovalNo가_있으면_예외를_던진다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260915-9999-0417");
        VanInquiryTcpResponse invalidResponse = new VanInquiryTcpResponse(
                "1", "INQUIRY_RESPONSE", inquiryRequestId(request), VanInquiryTargetType.CANCEL,
                request.targetTrxNo(), null, VanInquiryResultCode.SUCCESS, "VAN-CANCEL-INQ-017",
                VanInquiryStatus.CANCELLED, null, "CANCEL-APPROVAL-INQ-017",
                "REVERSAL-APPROVAL-INQ-017", null, LocalDateTime.of(2026, 9, 15, 12, 17)
        );

        assertInquiryFailure(request, invalidResponse, "VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_조회응답_timeout이면_timeout으로_변환한다() {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0105", 1);

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpResponseTimeoutException(
                        new RuntimeException("timeout")
                ));

        // when & then
        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(VanGatewayTimeoutException.class)
                .hasCauseInstanceOf(VanTcpResponseTimeoutException.class);
    }

    private VanInquiryRequest inquiryRequest(String posTrx, int attemptSeq) {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(posTrx)
                .targetAttemptSeq(attemptSeq)
                .vanTrxId("STORED-VAN-TRX")
                .cardLast4("4242")
                .build();
    }

    private VanInquiryRequest cancelInquiryRequest(String cancelPosTrx) {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(cancelPosTrx)
                .targetAttemptSeq(null)
                .vanTrxId("STORED-VAN-CANCEL-TRX")
                .cardLast4("4242")
                .build();
    }

    private VanInquiryRequest reversalInquiryRequest(String reversalPosTrx) {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.REVERSAL)
                .targetTrxNo(reversalPosTrx)
                .targetAttemptSeq(null)
                .vanTrxId(null)
                .cardLast4(null)
                .build();
    }

    private VanInquiryTcpResponse reversalInquiryTcpResponse(
            VanInquiryRequest request,
            VanInquiryResultCode resultCode,
            VanInquiryStatus status,
            String vanTrxId,
            String reversalApprovalNo,
            String declineCode
    ) {
        return new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                inquiryRequestId(request),
                VanInquiryTargetType.REVERSAL,
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                resultCode,
                vanTrxId,
                status,
                null,
                null,
                reversalApprovalNo,
                declineCode,
                LocalDateTime.of(2026, 9, 15, 12, 0)
        );
    }

    private String inquiryRequestId(VanInquiryRequest request) {
        return "INQUIRY-" + request.targetType() + "-" + request.targetTrxNo()
                + "-" + (request.targetAttemptSeq() == null ? "null" : request.targetAttemptSeq());
    }

    private void assertInquiryFailure(
            VanInquiryRequest request,
            VanInquiryTcpResponse response,
            String expectedMessage
    ) throws Exception {
        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(response));

        assertThatThrownBy(() -> inquiryOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage(expectedMessage);
    }

    private VanInquiryTcpResponse inquiryTcpResponse(
            VanInquiryRequest request,
            VanInquiryStatus status,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            LocalDateTime respondedAt
    ) {
        return new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-" + request.targetType() + "-" + request.targetTrxNo()
                        + "-" + (request.targetAttemptSeq() == null ? "null" : request.targetAttemptSeq()),
                request.targetType(),
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                VanInquiryResultCode.SUCCESS,
                vanTrxId,
                status,
                approvalNo,
                status == VanInquiryStatus.CANCELLED ? "CANCEL-APPROVAL-INQ-HELPER" : null,
                declineCode,
                respondedAt
        );
    }

}
