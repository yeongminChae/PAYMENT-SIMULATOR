package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpResponse;
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
        assertThat(tcpRequest.requestId()).isEqualTo("INQUIRY-2301-20260808-9999-0101-1");
        assertThat(tcpRequest.posTrx()).isEqualTo(request.posTrx());
        assertThat(tcpRequest.attemptSeq()).isEqualTo(request.attemptSeq());

        assertThat(response.posTrx()).isEqualTo(request.posTrx());
        assertThat(response.attemptSeq()).isEqualTo(request.attemptSeq());
        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQ-001");
        assertThat(response.approvalNo()).isEqualTo("APPROVAL-INQ-001");
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
        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.DECLINED);
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
        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.TIMEOUT);
        assertThat(response.vanTrxId()).isEqualTo("VAN-INQ-003");
    }

    @Test
    void TCP_조회응답의_correlation이_다르면_gateway_예외를_던진다() throws Exception {
        // given
        VanInquiryRequest request = inquiryRequest("2301-20260808-9999-0104", 1);
        VanInquiryTcpResponse mismatchedResponse = new VanInquiryTcpResponse(
                "1",
                "INQUIRY_RESPONSE",
                "INQUIRY-2301-20260808-9999-0104-1",
                request.posTrx(),
                2,
                "VAN-INQ-004",
                VanInquiryStatus.APPROVED,
                "APPROVAL-INQ-004",
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

    private VanInquiryRequest inquiryRequest(String posTrx, int attemptSeq) {
        return VanInquiryRequest.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .vanTrxId("STORED-VAN-TRX")
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
                "INQUIRY-" + request.posTrx() + "-" + request.attemptSeq(),
                request.posTrx(),
                request.attemptSeq(),
                vanTrxId,
                status,
                approvalNo,
                declineCode,
                respondedAt
        );
    }

}
