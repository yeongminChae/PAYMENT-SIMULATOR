package com.chaeyeongmin.payment_sim.van.gateway;

import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalStatus;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.approval.VanApprovalTcpResponse;
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

}
