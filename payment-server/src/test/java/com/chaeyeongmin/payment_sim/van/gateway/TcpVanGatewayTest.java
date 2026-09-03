package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpResultCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTcpResponse;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
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
class TcpVanGatewayTest {

    @Mock
    private VanTcpClient vanTcpClient;

    private ObjectMapper objectMapper;
    private TcpVanGateway tcpVanGateway;

    /**
     * Gateway 단위 테스트이므로 transport는 mock으로 두고,
     * Jackson 설정은 운영과 같이 LocalDateTime JSON을 문자열로 다루도록 맞춘다.
     */
    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        tcpVanGateway = new TcpVanGateway(objectMapper, vanTcpClient);
    }

    @Test
    void 기존_승인요청을_TCP_승인전문으로_변환하고_APPROVED_응답을_업무응답으로_변환한다() throws Exception {
        // given
        VanApproveRequest request = VanApproveRequest.builder()
                .posTrx("2301-20260808-9999-0001")
                .attemptSeq(1)
                .amount(10_000)
                .pan("1234567812345678")
                .expiryYyMm("2812")
                .cardBin("12345678")
                .cardLast4("5678")
                .build();

        VanApprovalTcpResponse tcpResponse = new VanApprovalTcpResponse(
                "1",
                "APPROVAL_RESPONSE",
                "APPROVAL-2301-20260808-9999-0001-1",
                request.posTrx(),
                request.attemptSeq(),
                "VAN-TCP-001",
                VanApprovalStatus.APPROVED,
                "APPROVAL-TCP-001",
                null,
                LocalDateTime.of(2026, 8, 24, 10, 30)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        // 기존 Payment 업무 DTO를 넘기면 Gateway가 TCP 전문 변환과 응답 업무 DTO 변환을 모두 수행한다.
        VanApproveResponse response = tcpVanGateway.approve(request);

        // then
        ArgumentCaptor<byte[]> requestPayloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(vanTcpClient).send(requestPayloadCaptor.capture());

        // transport로 넘어간 byte[]를 다시 TCP 요청 DTO로 읽어 매핑 필드를 검증한다.
        VanApprovalTcpRequest tcpRequest =
                objectMapper.readValue(requestPayloadCaptor.getValue(), VanApprovalTcpRequest.class);

        assertThat(tcpRequest.protocolVersion()).isEqualTo("1");
        assertThat(tcpRequest.messageType()).isEqualTo("APPROVAL");
        assertThat(tcpRequest.requestId()).isEqualTo("APPROVAL-2301-20260808-9999-0001-1");
        assertThat(tcpRequest.posTrx()).isEqualTo(request.posTrx());
        assertThat(tcpRequest.attemptSeq()).isEqualTo(request.attemptSeq());
        assertThat(tcpRequest.amount()).isEqualTo(request.amount());
        assertThat(tcpRequest.pan()).isEqualTo(request.pan());
        assertThat(tcpRequest.expiryYyMm()).isEqualTo(request.expiryYyMm());

        // TCP APPROVED 응답이 기존 Payment 업무 응답 모델로 변환됐는지 검증한다.
        assertThat(response.posTrx()).isEqualTo(request.posTrx());
        assertThat(response.attemptSeq()).isEqualTo(request.attemptSeq());
        assertThat(response.cardBin()).isEqualTo(request.cardBin());
        assertThat(response.cardLast4()).isEqualTo(request.cardLast4());
        assertThat(response.vanResult()).isEqualTo(VanResult.APPROVED);
        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-TCP-001");
        assertThat(response.approvalNo()).isEqualTo("APPROVAL-TCP-001");
        assertThat(response.declineCode()).isNull();
        assertThat(response.respondedAt()).isEqualTo(LocalDateTime.of(2026, 8, 24, 10, 30));
    }

    @Test
    void TCP_응답_timeout이면_gateway_timeout으로_변환한다() {
        // given
        VanApproveRequest request = VanApproveRequest.builder()
                .posTrx("2301-20260808-9999-0002")
                .attemptSeq(1)
                .amount(10_000)
                .pan("1234567812345678")
                .expiryYyMm("2812")
                .cardBin("12345678")
                .cardLast4("5678")
                .build();

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpResponseTimeoutException(
                        new RuntimeException("timeout")
                ));

        // when & then
        assertThatThrownBy(() -> tcpVanGateway.approve(request))
                .isInstanceOf(VanGatewayTimeoutException.class)
                .hasCauseInstanceOf(VanTcpResponseTimeoutException.class);
    }

    @Test
    void TCP_request_not_sent이면_gateway_request_not_sent로_변환한다() {
        VanApproveRequest request = VanApproveRequest.builder()
                .posTrx("2301-20260808-9999-0003")
                .attemptSeq(1)
                .amount(10_000)
                .pan("1234567812345678")
                .expiryYyMm("2812")
                .cardBin("12345678")
                .cardLast4("5678")
                .build();

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpRequestNotSentException(new RuntimeException("connect failed")));

        assertThatThrownBy(() -> tcpVanGateway.approve(request))
                .isInstanceOf(VanGatewayRequestNotSentException.class)
                .hasCauseInstanceOf(VanTcpRequestNotSentException.class);
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
        VanInquiryResponse response = tcpVanGateway.inquiry(request);

        // then
        ArgumentCaptor<byte[]> requestPayloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(vanTcpClient).send(requestPayloadCaptor.capture());

        VanInquiryTcpRequest tcpRequest =
                objectMapper.readValue(requestPayloadCaptor.getValue(), VanInquiryTcpRequest.class);

        assertThat(tcpRequest.protocolVersion()).isEqualTo("1");
        assertThat(tcpRequest.messageType()).isEqualTo("INQUIRY");
        assertThat(tcpRequest.requestId()).isEqualTo("INQUIRY-APPROVAL-2301-20260808-9999-0101-1");
        assertThat(tcpRequest.targetType()).isEqualTo(
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.APPROVAL
        );
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
        VanInquiryResponse response = tcpVanGateway.inquiry(request);

        // then
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.DECLINED);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.DO_NOT_HONOR);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQ-002");
    }

    @Test
    void TCP_UNKNOWN_조회응답을_UNKNOWN_TIMEOUT_업무응답으로_변환한다() throws Exception {
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
        VanInquiryResponse response = tcpVanGateway.inquiry(request);

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
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.APPROVAL,
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryResultCode.NOT_FOUND,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 34)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = tcpVanGateway.inquiry(request);

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
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                null,
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryResultCode.SUCCESS,
                "VAN-CANCEL-INQ-001",
                VanInquiryStatus.CANCELLED,
                null,
                "CANCEL-APPROVAL-INQ-001",
                null,
                LocalDateTime.of(2026, 8, 24, 11, 35)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanInquiryResponse response = tcpVanGateway.inquiry(request);

        assertThat(response.targetType()).isEqualTo(VanInquiryTargetType.CANCEL);
        assertThat(response.resultCode()).isEqualTo(VanInquiryResultCode.SUCCESS);
        assertThat(response.status()).isEqualTo(VanInquiryStatus.CANCELLED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-CANCEL-INQ-001");
        assertThat(response.approvalNo()).isNull();
        assertThat(response.cancelApprovalNo()).isEqualTo("CANCEL-APPROVAL-INQ-001");
    }

    @Test
    void TCP_조회응답의_correlation이_다르면_gateway_예외를_던진다() throws Exception {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0104", 1);
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-2301-20260808-9999-0104-1",
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.APPROVAL,
                request.targetTrxNo(),
                2,
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryResultCode.SUCCESS,
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
        assertThatThrownBy(() -> tcpVanGateway.inquiry(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_CANCEL_조회응답이_UNKNOWN_status이면_gateway_예외를_던진다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260808-9999-0108");
        VanInquiryTcpResponse invalidResponse = inquiryTcpResponse(
                request,
                VanInquiryStatus.UNKNOWN,
                "VAN-CANCEL-INQ-INVALID-001",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 36)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(invalidResponse));

        assertThatThrownBy(() -> tcpVanGateway.inquiry(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_APPROVAL_조회응답이_CANCELLED_status이면_gateway_예외를_던진다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0109", 1);
        VanInquiryTcpResponse invalidResponse = inquiryTcpResponse(
                request,
                VanInquiryStatus.CANCELLED,
                "VAN-INQ-INVALID-001",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 37)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(invalidResponse));

        assertThatThrownBy(() -> tcpVanGateway.inquiry(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_INVALID");
    }

    @Test
    void TCP_조회응답의_targetType_correlation이_다르면_gateway_예외를_던진다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0110", 1);
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-APPROVAL-" + request.targetTrxNo() + "-" + request.targetAttemptSeq(),
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryResultCode.SUCCESS,
                "VAN-INQ-005",
                VanInquiryStatus.CANCELLED,
                null,
                "CANCEL-APPROVAL-INQ-005",
                null,
                LocalDateTime.of(2026, 8, 24, 11, 38)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        assertThatThrownBy(() -> tcpVanGateway.inquiry(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_조회응답의_targetTrxNo_correlation이_다르면_gateway_예외를_던진다() throws Exception {
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0111", 1);
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-APPROVAL-" + request.targetTrxNo() + "-" + request.targetAttemptSeq(),
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.APPROVAL,
                "2301-20260808-9999-DIFF",
                request.targetAttemptSeq(),
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryResultCode.SUCCESS,
                "VAN-INQ-006",
                VanInquiryStatus.APPROVED,
                "APPROVAL-INQ-006",
                null,
                null,
                LocalDateTime.of(2026, 8, 24, 11, 39)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        assertThatThrownBy(() -> tcpVanGateway.inquiry(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_조회응답의_nullable_targetAttemptSeq_correlation이_다르면_gateway_예외를_던진다() throws Exception {
        VanInquiryRequest request = cancelInquiryRequest("2301-20260808-9999-0112");
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-CANCEL-" + request.targetTrxNo() + "-null",
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.CANCEL,
                request.targetTrxNo(),
                1,
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryResultCode.SUCCESS,
                "VAN-CANCEL-INQ-002",
                VanInquiryStatus.CANCELLED,
                null,
                "CANCEL-APPROVAL-INQ-002",
                null,
                LocalDateTime.of(2026, 8, 24, 11, 40)
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        assertThatThrownBy(() -> tcpVanGateway.inquiry(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_INQUIRY_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_조회응답_timeout이면_gateway_timeout으로_변환한다() {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0105", 1);

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpResponseTimeoutException(
                        new RuntimeException("timeout")
                ));

        // when & then
        assertThatThrownBy(() -> tcpVanGateway.inquiry(request))
                .isInstanceOf(VanGatewayTimeoutException.class)
                .hasCauseInstanceOf(VanTcpResponseTimeoutException.class);
    }

    @Test
    void 기존_취소요청을_TCP_CANCEL_전문으로_변환하고_CANCELLED_응답을_업무응답으로_변환한다() throws Exception {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0201");
        VanCancelTcpResponse tcpResponse = cancelTcpResponse(
                request,
                VanCancelTcpStatus.CANCELLED,
                VanCancelTcpResultCode.SUCCESS,
                "VAN-CANCEL-TCP-001",
                "CANCEL-APPROVAL-TCP-001",
                null
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        VanCancelResponse response = tcpVanGateway.cancel(request);

        // then
        ArgumentCaptor<byte[]> requestPayloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(vanTcpClient).send(requestPayloadCaptor.capture());

        VanCancelTcpRequest tcpRequest =
                objectMapper.readValue(requestPayloadCaptor.getValue(), VanCancelTcpRequest.class);

        assertThat(tcpRequest.protocolVersion()).isEqualTo("1");
        assertThat(tcpRequest.messageType()).isEqualTo("CANCEL");
        assertThat(tcpRequest.requestId()).isEqualTo("CANCEL-" + request.posTrx());
        assertThat(tcpRequest.cancelPosTrx()).isEqualTo(request.posTrx());
        assertThat(tcpRequest.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(tcpRequest.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(tcpRequest.originalVanTrxId()).isEqualTo(request.vanTrxId());
        assertThat(tcpRequest.originalApprovalNo()).isEqualTo(request.approvalNo());
        assertThat(tcpRequest.amount()).isEqualTo(request.amount());

        assertThat(response.posTrx()).isEqualTo(request.posTrx());
        assertThat(response.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(response.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(response.cancelStatus()).isEqualTo(CancelStatus.CANCELLED);
        assertThat(response.cancelApprovalNo()).isEqualTo("CANCEL-APPROVAL-TCP-001");
        assertThat(response.declineCode()).isNull();
        assertThat(response.vanTrxId()).isEqualTo("VAN-CANCEL-TCP-001");
        assertThat(response.message()).isEqualTo("SUCCESS");
        assertThat(response.respondedAt()).isNotNull();
    }

    @Test
    void TCP_ORIGINAL_MISMATCH_취소응답을_CANCEL_DECLINED_업무응답으로_변환한다() throws Exception {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0202");
        VanCancelTcpResponse tcpResponse = cancelTcpResponse(
                request,
                VanCancelTcpStatus.CANCEL_DECLINED,
                VanCancelTcpResultCode.ORIGINAL_MISMATCH,
                "VAN-CANCEL-TCP-002",
                null,
                "ORIGINAL_MISMATCH"
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        VanCancelResponse response = tcpVanGateway.cancel(request);

        // then
        assertThat(response.posTrx()).isEqualTo(request.posTrx());
        assertThat(response.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(response.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(response.cancelStatus()).isEqualTo(CancelStatus.CANCEL_DECLINED);
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.ORIGINAL_MISMATCH);
        assertThat(response.vanTrxId()).isEqualTo("VAN-CANCEL-TCP-002");
        assertThat(response.message()).isEqualTo("ORIGINAL_MISMATCH");
        assertThat(response.respondedAt()).isNotNull();
    }

    @Test
    void TCP_CANCEL_응답의_correlation이_다르면_gateway_예외를_던진다() throws Exception {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0203");
        VanCancelTcpResponse mismatchedResponse = new VanCancelTcpResponse(
                "1",
                "CANCEL_RESPONSE",
                "CANCEL-DIFFERENT",
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                "VAN-CANCEL-TCP-003",
                VanCancelTcpStatus.CANCELLED,
                VanCancelTcpResultCode.SUCCESS,
                "CANCEL-APPROVAL-TCP-003",
                null
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        // when & then
        assertThatThrownBy(() -> tcpVanGateway.cancel(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_CANCEL_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_CANCEL_응답의_상태_조합이_모순이면_gateway_예외를_던진다() throws Exception {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0204");
        VanCancelTcpResponse invalidResponse = cancelTcpResponse(
                request,
                VanCancelTcpStatus.CANCELLED,
                VanCancelTcpResultCode.ORIGINAL_NOT_FOUND,
                "VAN-CANCEL-TCP-004",
                "CANCEL-APPROVAL-TCP-004",
                "ORIGINAL_NOT_FOUND"
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(invalidResponse));

        // when & then
        assertThatThrownBy(() -> tcpVanGateway.cancel(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_CANCEL_RESPONSE_INVALID");
    }

    @Test
    void TCP_CANCEL_응답_timeout이면_gateway_timeout으로_변환한다() {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0205");

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpResponseTimeoutException(
                        new RuntimeException("timeout")
                ));

        // when & then
        assertThatThrownBy(() -> tcpVanGateway.cancel(request))
                .isInstanceOf(VanGatewayTimeoutException.class)
                .hasCauseInstanceOf(VanTcpResponseTimeoutException.class);
    }

    @Test
    void TCP_CANCEL_request_not_sent이면_gateway_request_not_sent로_변환한다() {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0206");

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpRequestNotSentException(new RuntimeException("connect failed")));

        // when & then
        assertThatThrownBy(() -> tcpVanGateway.cancel(request))
                .isInstanceOf(VanGatewayRequestNotSentException.class)
                .hasCauseInstanceOf(VanTcpRequestNotSentException.class);
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
                switch (request.targetType()) {
                    case APPROVAL -> com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.APPROVAL;
                    case CANCEL -> com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryTargetType.CANCEL;
                },
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryResultCode.SUCCESS,
                vanTrxId,
                status,
                approvalNo,
                status == VanInquiryStatus.CANCELLED ? "CANCEL-APPROVAL-INQ-HELPER" : null,
                declineCode,
                respondedAt
        );
    }

    private VanCancelRequest cancelRequest(String cancelPosTrx) {
        return VanCancelRequest.builder()
                .posTrx(cancelPosTrx)
                .originalPosTrx("2301-20260808-9999-0101")
                .originalAttemptSeq(1)
                .amount(10_000)
                .approvalNo("APPROVAL-CANCEL-001")
                .vanTrxId("VAN-APPROVAL-CANCEL-001")
                .cardLast4("4242")
                .build();
    }

    private VanCancelTcpResponse cancelTcpResponse(
            VanCancelRequest request,
            VanCancelTcpStatus status,
            VanCancelTcpResultCode resultCode,
            String vanCancelTrxId,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new VanCancelTcpResponse(
                "1",
                "CANCEL_RESPONSE",
                "CANCEL-" + request.posTrx(),
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                vanCancelTrxId,
                status,
                resultCode,
                cancelApprovalNo,
                declineCode
        );
    }

}
