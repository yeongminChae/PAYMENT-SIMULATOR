package com.chaeyeongmin.payment_sim.van.gateway.impl.tcp;

import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.VanTcpClient;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.client.tcp.exception.VanTcpResponseTimeoutException;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpRequest;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpResponse;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpResultCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.cancel.VanCancelTcpStatus;
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
class TcpCancelOperationTest {

    @Mock
    private VanTcpClient vanTcpClient;

    private ObjectMapper objectMapper;
    private TcpCancelOperation cancelOperation;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        cancelOperation = new TcpCancelOperation(
                objectMapper,
                vanTcpClient
        );
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

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        VanCancelResponse response = cancelOperation.execute(request);

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

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        // when
        VanCancelResponse response = cancelOperation.execute(request);

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
    void TCP_CANCEL_응답의_correlation이_다르면_예외를_던진다() throws Exception {
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

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(mismatchedResponse));

        // when & then
        assertThatThrownBy(() -> cancelOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_CANCEL_RESPONSE_MISMATCH");
    }

    @Test
    void TCP_CANCEL_응답의_상태_조합이_모순이면_예외를_던진다() throws Exception {
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

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(invalidResponse));

        // when & then
        assertThatThrownBy(() -> cancelOperation.execute(request))
                .isInstanceOf(TcpVanGatewayException.class)
                .hasMessage("VAN_TCP_CANCEL_RESPONSE_INVALID");
    }

    @Test
    void TCP_CANCEL_응답_timeout이면_timeout으로_변환한다() {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0205");

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpResponseTimeoutException(
                        new RuntimeException("timeout")
                ));

        // when & then
        assertThatThrownBy(() -> cancelOperation.execute(request))
                .isInstanceOf(VanGatewayTimeoutException.class)
                .hasCauseInstanceOf(VanTcpResponseTimeoutException.class);
    }

    @Test
    void TCP_CANCEL_request_not_sent이면_request_not_sent로_변환한다() {
        // given
        VanCancelRequest request = cancelRequest("2301-20260808-9999-0206");

        when(vanTcpClient.send(any(byte[].class)))
                .thenThrow(new VanTcpRequestNotSentException(new RuntimeException("connect failed")));

        // when & then
        assertThatThrownBy(() -> cancelOperation.execute(request))
                .isInstanceOf(VanGatewayRequestNotSentException.class)
                .hasCauseInstanceOf(VanTcpRequestNotSentException.class);
    }

    @Test
    void cancel이_이미_reversal된_원승인이라는_TCP응답을_정상_변환한다() throws Exception {
        VanCancelRequest request = cancelRequest("2301-20260907-9999-0204");
        VanCancelTcpResponse tcpResponse  = cancelTcpResponse(
                request,
                VanCancelTcpStatus.CANCEL_DECLINED,
                VanCancelTcpResultCode.ALREADY_REVERSED,
                "VAN-CANCEL-TCP-004",
                null,
                "ALREADY_REVERSED"
        );

        when(vanTcpClient.send(any(byte[].class))).thenReturn(objectMapper.writeValueAsBytes(tcpResponse));

        VanCancelResponse result = cancelOperation.execute(request);
        assertThat(result.cancelStatus()).isEqualTo(CancelStatus.CANCEL_DECLINED);
        assertThat(result.message()).isEqualTo("ALREADY_REVERSED");
        assertThat(result.declineCode()).isEqualTo(VanDeclineCode.ALREADY_REVERSED);
        assertDoesNotThrow(() -> cancelOperation.execute(request));
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
