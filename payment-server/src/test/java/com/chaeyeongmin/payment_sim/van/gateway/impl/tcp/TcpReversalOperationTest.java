package com.chaeyeongmin.payment_sim.van.gateway.impl.tcp;

import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpResultCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.reversal.VanReversalTcpStatus;
import com.chaeyeongmin.payment_sim.van.gateway.exception.TcpVanGatewayException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TcpReversalOperationTest {

    @Mock
    private VanTcpClient vanTcpClient;

    private ObjectMapper objectMapper;
    private TcpReversalOperation reversalOperation;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        reversalOperation = new TcpReversalOperation(
                objectMapper,
                vanTcpClient
        );
    }

    @Test
    void 기존_reversal요청을_TCP_REVERSAL_전문으로_변환하고_REVERSED_SUCCESS_응답을_업무응답으로_변환한다() throws Exception {
        VanReversalRequest request = reversalRequest("2301-20260808-9999-0301");
        VanReversalTcpResponse tcpResponse = reversalTcpResponse(
                request,
                VanReversalTcpStatus.REVERSED,
                VanReversalTcpResultCode.SUCCESS,
                "VAN-REVERSAL-TCP-001",
                "REVERSAL-APPROVAL-TCP-001",
                null
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanReversalResponse response = reversalOperation.execute(request);

        ArgumentCaptor<byte[]> requestPayloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(vanTcpClient).send(requestPayloadCaptor.capture());

        VanReversalTcpRequest tcpRequest =
                objectMapper.readValue(requestPayloadCaptor.getValue(), VanReversalTcpRequest.class);

        assertThat(tcpRequest.protocolVersion()).isEqualTo("1");
        assertThat(tcpRequest.messageType()).isEqualTo("REVERSAL");
        assertThat(tcpRequest.requestId()).isEqualTo("REVERSAL-" + request.reversalPosTrx());
        assertThat(tcpRequest.reversalPosTrx()).isEqualTo(request.reversalPosTrx());
        assertThat(tcpRequest.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(tcpRequest.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(tcpRequest.amount()).isEqualTo(request.amount());

        assertThat(response.reversalPosTrx()).isEqualTo(request.reversalPosTrx());
        assertThat(response.originalPosTrx()).isEqualTo(request.originalPosTrx());
        assertThat(response.originalAttemptSeq()).isEqualTo(request.originalAttemptSeq());
        assertThat(response.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(response.resultCode()).isEqualTo(VanReversalResultCode.SUCCESS);
        assertThat(response.reversalApprovalNo()).isEqualTo("REVERSAL-APPROVAL-TCP-001");
        assertThat(response.declineCode()).isNull();
        assertThat(response.vanReversalTrxId()).isEqualTo("VAN-REVERSAL-TCP-001");
        assertThat(response.respondedAt()).isNotNull();
    }

    @Test
    void TCP_ALREADY_REVERSED_reversal응답을_REVERSED_업무응답으로_변환한다() throws Exception {
        VanReversalRequest request = reversalRequest("2301-20260808-9999-0302");
        VanReversalTcpResponse tcpResponse = reversalTcpResponse(
                request,
                VanReversalTcpStatus.REVERSED,
                VanReversalTcpResultCode.ALREADY_REVERSED,
                "VAN-REVERSAL-TCP-002",
                "REVERSAL-APPROVAL-TCP-002",
                null
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanReversalResponse response = reversalOperation.execute(request);

        assertThat(response.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(response.resultCode()).isEqualTo(VanReversalResultCode.ALREADY_REVERSED);
        assertThat(response.reversalApprovalNo()).isEqualTo("REVERSAL-APPROVAL-TCP-002");
        assertThat(response.declineCode()).isNull();
        assertThat(response.vanReversalTrxId()).isEqualTo("VAN-REVERSAL-TCP-002");
    }

    @Test
    void TCP_REVERSAL_DECLINED_reversal응답을_업무응답으로_변환한다() throws Exception {
        VanReversalRequest request = reversalRequest("2301-20260808-9999-0303");
        VanReversalTcpResponse tcpResponse = reversalTcpResponse(
                request,
                VanReversalTcpStatus.REVERSAL_DECLINED,
                VanReversalTcpResultCode.ORIGINAL_NOT_REVERSIBLE,
                "VAN-REVERSAL-TCP-003",
                null,
                "ORIGINAL_NOT_REVERSIBLE"
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanReversalResponse response = reversalOperation.execute(request);

        assertThat(response.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(response.resultCode()).isEqualTo(VanReversalResultCode.ORIGINAL_NOT_REVERSIBLE);
        assertThat(response.reversalApprovalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.ORIGINAL_NOT_REVERSIBLE);
        assertThat(response.vanReversalTrxId()).isEqualTo("VAN-REVERSAL-TCP-003");
    }

    @Test
    void TCP_REVERSAL_응답의_correlation이_다르면_예외를_던진다() throws Exception {
        VanReversalRequest request = reversalRequest("2301-20260808-9999-0304");
        VanReversalTcpResponse mismatchedResponse = new VanReversalTcpResponse(
                "1",
                "REVERSAL_RESPONSE",
                "REVERSAL-DIFFERENT",
                request.reversalPosTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                "VAN-REVERSAL-TCP-004",
                VanReversalTcpStatus.REVERSED,
                VanReversalTcpResultCode.SUCCESS,
                "REVERSAL-APPROVAL-TCP-004",
                null
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        assertThatThrownBy(() -> reversalOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_REVERSAL_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_REVERSAL_응답의_상태_조합이_모순이면_예외를_던진다() throws Exception {
        VanReversalRequest request = reversalRequest("2301-20260808-9999-0305");
        VanReversalTcpResponse invalidResponse = reversalTcpResponse(
                request,
                VanReversalTcpStatus.REVERSED,
                VanReversalTcpResultCode.ORIGINAL_NOT_FOUND,
                "VAN-REVERSAL-TCP-005",
                "REVERSAL-APPROVAL-TCP-005",
                "ORIGINAL_NOT_FOUND"
        );

        when(vanTcpClient.send(any(byte[].class)))
                .thenReturn(objectMapper.writeValueAsBytes(invalidResponse));

        assertThatThrownBy(() -> reversalOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_REVERSAL_RESPONSE_INVALID");
    }

    @Test
    void TCP_REVERSAL_request_not_sent이면_request_not_sent로_변환한다() {
        VanReversalRequest request = reversalRequest("2301-20260808-9999-0306");

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpRequestNotSentException(new RuntimeException("connect failed")));

        assertThatThrownBy(() -> reversalOperation.execute(request))
                .isInstanceOf(VanGatewayRequestNotSentException.class)
                .hasCauseInstanceOf(VanTcpRequestNotSentException.class);
    }

    @Test
    void TCP_REVERSAL_응답_timeout이면_timeout으로_변환한다() {
        VanReversalRequest request = reversalRequest("2301-20260808-9999-0307");

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpResponseTimeoutException(
                        new RuntimeException("timeout")
                ));

        assertThatThrownBy(() -> reversalOperation.execute(request))
                .isInstanceOf(VanGatewayTimeoutException.class)
                .hasCauseInstanceOf(VanTcpResponseTimeoutException.class);
    }

    @Test
    void reversal이_이미_cancel된_원승인이라는_TCP응답을_정상_변환한다() throws Exception {
        VanReversalRequest request = reversalRequest("2301-20260907-9999-0301");
        VanReversalTcpResponse tcpResponse = reversalTcpResponse(
                request,
                VanReversalTcpStatus.REVERSAL_DECLINED,
                VanReversalTcpResultCode.ALREADY_CANCELLED,
                "VAN-REVERSAL-TCP-001",
                null,
                "ALREADY_CANCELLED"
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanReversalResponse result = reversalOperation.execute(request);
        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(result.resultCode()).isEqualTo(VanReversalResultCode.ALREADY_CANCELLED);
        assertThat(result.declineCode()).isEqualTo(VanDeclineCode.ALREADY_CANCELLED);
        assertDoesNotThrow(() -> reversalOperation.execute(request));
    }

    private VanReversalRequest reversalRequest(String reversalPosTrx) {
        return VanReversalRequest.builder()
                .reversalPosTrx(reversalPosTrx)
                .originalPosTrx("2301-20260808-9999-0101")
                .originalAttemptSeq(1)
                .amount(10_000)
                .build();
    }

    private VanReversalTcpResponse reversalTcpResponse(
            VanReversalRequest request,
            VanReversalTcpStatus status,
            VanReversalTcpResultCode resultCode,
            String vanReversalTrxId,
            String reversalApprovalNo,
            String declineCode
    ) {
        return new VanReversalTcpResponse(
                "1",
                "REVERSAL_RESPONSE",
                "REVERSAL-" + request.reversalPosTrx(),
                request.reversalPosTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                vanReversalTrxId,
                status,
                resultCode,
                reversalApprovalNo,
                declineCode
        );
    }

}
