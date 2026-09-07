package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelInquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelResponseFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.PaymentCancelTransactionService;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.cancel.CancelCardVerificationPolicy;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanInquiryAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cancel inquiry orchestration 테스트.
 *
 * <p>
 * 여기서는 DB transaction 내부 로직보다 "어떤 상태에서 VAN Inquiry(CANCEL)를 호출하는지"와
 * "NOT_FOUND/timeout에서 UNKNOWN_TIMEOUT을 건드리지 않는지"를 주로 검증한다.
 */
class PaymentCancelInquiryServiceImplTest {

    private static final String CANCEL_POS_TRX = "2376-20260903-9991-2001";
    private static final String ORIGINAL_POS_TRX = "2376-20260903-9991-1001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final String VAN_CANCEL_TRX_ID = "VAN-CANCEL-INQUIRY-0001";
    private static final String CANCEL_APPROVAL_NO = "CANCEL-APPROVAL-0001";

    private PaymentCancelInquiryServiceImpl service;
    private PaymentCancelRepository cancelRepository;
    private VanInquiryAssembler assembler;
    private VanGateway gateway;

    @BeforeEach
    void setUp() {
        cancelRepository = mock(PaymentCancelRepository.class);
        assembler = mock(VanInquiryAssembler.class);
        gateway = mock(VanGateway.class);

        PaymentCancelTransactionService transactionService = new PaymentCancelTransactionService(
                cancelRepository,
                mock(PaymentAttemptRepository.class),
                mock(CancelCardVerificationPolicy.class),
                new CancelResponseFactory(),
                mock(CancelEventRecorder.class)
        );

        service = new PaymentCancelInquiryServiceImpl(
                transactionService,
                cancelRepository,
                assembler,
                gateway
        );
    }

    @Test
    @DisplayName("취소 row가 없으면 NOT_FOUND 예외를 던지고 VAN을 호출하지 않는다")
    void inquiry_cancelNotFound_shouldThrowNotFound_withoutVanCall() {
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.inquiry(new CancelInquiryRequest(CANCEL_POS_TRX))
        );

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verifyNoInteractions(assembler, gateway);
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("PENDING이면 RETRY_LATER를 반환하고 VAN을 호출하지 않는다")
    void inquiry_pending_shouldReturnRetryLater_withoutVanCall() {
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.PENDING, null, null)));

        CancelResponse response = service.inquiry(new CancelInquiryRequest(CANCEL_POS_TRX));

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        verifyNoInteractions(assembler, gateway);
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("이미 CANCELLED이면 저장된 CANCELLED 결과를 반환하고 VAN을 호출하지 않는다")
    void inquiry_cancelled_shouldReturnStoredCancelled_withoutVanCall() {
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.CANCELLED, CANCEL_APPROVAL_NO, null)));

        CancelResponse response = service.inquiry(new CancelInquiryRequest(CANCEL_POS_TRX));

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals(CANCEL_APPROVAL_NO, response.cancelApprovalNo());
        verifyNoInteractions(assembler, gateway);
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("UNKNOWN_TIMEOUT이고 VAN NOT_FOUND이면 RETRY_LATER를 반환하고 DB를 update하지 않는다")
    void inquiry_unknownTimeoutAndVanNotFound_shouldReturnRetryLater_withoutDbUpdate() {
        VanInquiryRequest request = vanInquiryRequest();

        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT")));
        when(assembler.getCancelInquiryRequest(CANCEL_POS_TRX)).thenReturn(request);
        when(gateway.inquiry(request)).thenReturn(vanNotFoundResponse());

        CancelResponse response = service.inquiry(new CancelInquiryRequest(CANCEL_POS_TRX));

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        verify(gateway).inquiry(request);
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("UNKNOWN_TIMEOUT이고 VAN Inquiry timeout이면 RETRY_LATER를 반환하고 DB를 update하지 않는다")
    void inquiry_unknownTimeoutAndVanTimeout_shouldReturnRetryLater_withoutDbUpdate() {
        VanInquiryRequest request = vanInquiryRequest();

        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT")));
        when(assembler.getCancelInquiryRequest(CANCEL_POS_TRX)).thenReturn(request);
        when(gateway.inquiry(request)).thenThrow(
                new VanGatewayTimeoutException(new RuntimeException("timeout"))
        );

        CancelResponse response = service.inquiry(new CancelInquiryRequest(CANCEL_POS_TRX));

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        verify(gateway).inquiry(request);
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("UNKNOWN_TIMEOUT이고 VAN CANCELLED면 DB를 CANCELLED로 갱신하고 취소 승인번호를 반환한다")
    void inquiry_unknownTimeoutAndVanCancelled_shouldUpdateDbAndReturnCancelled() {
        VanInquiryRequest request = vanInquiryRequest();
        PaymentCancel updated = cancel(CancelStatus.CANCELLED, CANCEL_APPROVAL_NO, null);

        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT")));
        when(assembler.getCancelInquiryRequest(CANCEL_POS_TRX)).thenReturn(request);
        when(gateway.inquiry(request)).thenReturn(vanCancelledResponse());
        when(cancelRepository.updateUnknownTimeoutToFinal(any()))
                .thenReturn(Optional.of(updated));

        CancelResponse response = service.inquiry(new CancelInquiryRequest(CANCEL_POS_TRX));

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals(CANCEL_APPROVAL_NO, response.cancelApprovalNo());

        ArgumentCaptor<CancelResultUpdateParam> captor =
                ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(cancelRepository).updateUnknownTimeoutToFinal(captor.capture());
        assertEquals(CancelStatus.CANCELLED, captor.getValue().cancelStatus());
        assertEquals(VAN_CANCEL_TRX_ID, captor.getValue().vanCancelTrxId());
        assertEquals(CANCEL_APPROVAL_NO, captor.getValue().cancelApprovalNo());
    }

    @Test
    @DisplayName("UNKNOWN_TIMEOUT이고 VAN CANCEL_DECLINED면 DB를 CANCEL_DECLINED로 갱신하고 거절코드를 반환한다")
    void inquiry_unknownTimeoutAndVanCancelDeclined_shouldUpdateDbAndReturnDeclined() {
        VanInquiryRequest request = vanInquiryRequest();
        PaymentCancel updated = cancel(CancelStatus.CANCEL_DECLINED, null, VanDeclineCode.DO_NOT_HONOR.code());

        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT")));
        when(assembler.getCancelInquiryRequest(CANCEL_POS_TRX)).thenReturn(request);
        when(gateway.inquiry(request)).thenReturn(vanCancelDeclinedResponse());
        when(cancelRepository.updateUnknownTimeoutToFinal(any()))
                .thenReturn(Optional.of(updated));

        CancelResponse response = service.inquiry(new CancelInquiryRequest(CANCEL_POS_TRX));

        assertEquals(CancelResultStatus.CANCEL_DECLINED, response.cancelStatus());
        assertEquals(VanDeclineCode.DO_NOT_HONOR.code(), response.declineCode());

        ArgumentCaptor<CancelResultUpdateParam> captor =
                ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(cancelRepository).updateUnknownTimeoutToFinal(captor.capture());
        assertEquals(CancelStatus.CANCEL_DECLINED, captor.getValue().cancelStatus());
        assertEquals(VAN_CANCEL_TRX_ID, captor.getValue().vanCancelTrxId());
        assertEquals(VanDeclineCode.DO_NOT_HONOR.code(), captor.getValue().declineCode());
    }

    private PaymentCancel cancel(
            CancelStatus status,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new PaymentCancel(
                CANCEL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                status,
                VAN_CANCEL_TRX_ID,
                cancelApprovalNo,
                declineCode
        );
    }

    private VanInquiryRequest vanInquiryRequest() {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(CANCEL_POS_TRX)
                .targetAttemptSeq(null)
                .vanTrxId(null)
                .cardLast4(null)
                .build();
    }

    private VanInquiryResponse vanNotFoundResponse() {
        return baseVanResponse()
                .resultCode(VanInquiryResultCode.NOT_FOUND)
                .status(null)
                .vanTrxId(null)
                .cancelApprovalNo(null)
                .declineCode(null)
                .message("NOT_FOUND")
                .build();
    }

    private VanInquiryResponse vanCancelledResponse() {
        return baseVanResponse()
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.CANCELLED)
                .vanTrxId(VAN_CANCEL_TRX_ID)
                .cancelApprovalNo(CANCEL_APPROVAL_NO)
                .declineCode(null)
                .message("CANCELLED")
                .build();
    }

    private VanInquiryResponse vanCancelDeclinedResponse() {
        return baseVanResponse()
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.CANCEL_DECLINED)
                .vanTrxId(VAN_CANCEL_TRX_ID)
                .cancelApprovalNo(null)
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .message("CANCEL_DECLINED")
                .build();
    }

    private VanInquiryResponse.VanInquiryResponseBuilder baseVanResponse() {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(CANCEL_POS_TRX)
                .targetAttemptSeq(null)
                .approvalNo(null)
                .respondedAt(LocalDateTime.now());
    }
}
