package com.chaeyeongmin.payment_sim.van.gateway.impl;

import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpApprovalOperation;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpCancelOperation;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpInquiryOperation;
import com.chaeyeongmin.payment_sim.van.gateway.impl.tcp.TcpReversalOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TcpVanGatewayTest {

    @Mock
    private TcpApprovalOperation approvalOperation;

    @Mock
    private TcpInquiryOperation inquiryOperation;

    @Mock
    private TcpCancelOperation cancelOperation;

    @Mock
    private TcpReversalOperation reversalOperation;

    private TcpVanGateway tcpVanGateway;

    @BeforeEach
    void setUp() {
        tcpVanGateway = new TcpVanGateway(
                approvalOperation,
                inquiryOperation,
                cancelOperation,
                reversalOperation
        );
    }

    @Test
    void approve는_approvalOperation에_위임한다() {
        VanApproveRequest request = VanApproveRequest.builder()
                .posTrx("2301-20260808-9999-0001")
                .attemptSeq(1)
                .amount(10_000)
                .pan("1234567812345678")
                .expiryYyMm("2812")
                .cardBin("12345678")
                .cardLast4("5678")
                .build();
        VanApproveResponse expected = VanApproveResponse.builder()
                .posTrx(request.posTrx())
                .attemptSeq(request.attemptSeq())
                .cardBin(request.cardBin())
                .cardLast4(request.cardLast4())
                .vanResult(VanResult.APPROVED)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo("APPROVAL-TCP-001")
                .vanTrxId("VAN-TCP-001")
                .message("APPROVED")
                .respondedAt(LocalDateTime.of(2026, 8, 24, 10, 30))
                .build();

        when(approvalOperation.execute(request)).thenReturn(expected);

        VanApproveResponse actual = tcpVanGateway.approve(request);

        assertThat(actual).isSameAs(expected);
        verify(approvalOperation).execute(request);
    }

    @Test
    void inquiry는_inquiryOperation에_위임한다() {
        VanInquiryRequest request = VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo("2301-20260808-9999-0101")
                .targetAttemptSeq(1)
                .vanTrxId("STORED-VAN-TRX")
                .cardLast4("4242")
                .build();
        VanInquiryResponse expected = VanInquiryResponse.builder()
                .targetType(request.targetType())
                .targetTrxNo(request.targetTrxNo())
                .targetAttemptSeq(request.targetAttemptSeq())
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.APPROVED)
                .vanTrxId("VAN-INQ-001")
                .approvalNo("APPROVAL-INQ-001")
                .message("APPROVED")
                .respondedAt(LocalDateTime.of(2026, 8, 24, 11, 30))
                .build();

        when(inquiryOperation.execute(request)).thenReturn(expected);

        VanInquiryResponse actual = tcpVanGateway.inquiry(request);

        assertThat(actual).isSameAs(expected);
        verify(inquiryOperation).execute(request);
    }

    @Test
    void cancel은_cancelOperation에_위임한다() {
        VanCancelRequest request = VanCancelRequest.builder()
                .posTrx("2301-20260808-9999-0201")
                .originalPosTrx("2301-20260808-9999-0101")
                .originalAttemptSeq(1)
                .amount(10_000)
                .approvalNo("APPROVAL-CANCEL-001")
                .vanTrxId("VAN-APPROVAL-CANCEL-001")
                .cardLast4("4242")
                .build();
        VanCancelResponse expected = VanCancelResponse.builder()
                .posTrx(request.posTrx())
                .originalPosTrx(request.originalPosTrx())
                .originalAttemptSeq(request.originalAttemptSeq())
                .cancelStatus(CancelStatus.CANCELLED)
                .cancelApprovalNo("CANCEL-APPROVAL-TCP-001")
                .vanTrxId("VAN-CANCEL-TCP-001")
                .message("SUCCESS")
                .respondedAt(LocalDateTime.of(2026, 8, 24, 12, 0))
                .build();

        when(cancelOperation.execute(request)).thenReturn(expected);

        VanCancelResponse actual = tcpVanGateway.cancel(request);

        assertThat(actual).isSameAs(expected);
        verify(cancelOperation).execute(request);
    }

    @Test
    void reversal은_reversalOperation에_위임한다() {
        VanReversalRequest request = VanReversalRequest.builder()
                .reversalPosTrx("2301-20260808-9999-0301")
                .originalPosTrx("2301-20260808-9999-0101")
                .originalAttemptSeq(1)
                .amount(10_000)
                .build();
        VanReversalResponse expected = VanReversalResponse.builder()
                .reversalPosTrx(request.reversalPosTrx())
                .originalPosTrx(request.originalPosTrx())
                .originalAttemptSeq(request.originalAttemptSeq())
                .reversalStatus(VanReversalStatus.REVERSED)
                .resultCode(VanReversalResultCode.SUCCESS)
                .reversalApprovalNo("REVERSAL-APPROVAL-TCP-001")
                .vanReversalTrxId("VAN-REVERSAL-TCP-001")
                .respondedAt(LocalDateTime.of(2026, 8, 24, 12, 30))
                .build();

        when(reversalOperation.execute(request)).thenReturn(expected);

        VanReversalResponse actual = tcpVanGateway.reversal(request);

        assertThat(actual).isSameAs(expected);
        verify(reversalOperation).execute(request);
    }

}
