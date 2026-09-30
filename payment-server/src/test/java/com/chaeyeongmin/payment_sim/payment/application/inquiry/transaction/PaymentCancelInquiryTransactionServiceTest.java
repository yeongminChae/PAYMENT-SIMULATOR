package com.chaeyeongmin.payment_sim.payment.application.inquiry.transaction;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResultStatus;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.PaymentCancel;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
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
import static org.mockito.Mockito.when;

/**
 * Cancel inquiry 최종 저장 transaction의 동시성 수렴 테스트.
 *
 * <p>
 * updateUnknownTimeoutToFinal이 0 row를 반환하는 상황은 보통 같은 UNKNOWN_TIMEOUT row를
 * 다른 요청이 먼저 확정했다는 뜻이다. 이때 기존 재취소 응답 규칙을 쓰지 않고,
 * 현재 DB 상태를 inquiry 응답으로 그대로 돌려주는지가 핵심이다.
 */
class PaymentCancelInquiryTransactionServiceTest {

    private static final String CANCEL_POS_TRX = "2376-20260903-9991-2001";
    private static final String ORIGINAL_POS_TRX = "2376-20260903-9991-1001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final String VAN_CANCEL_TRX_ID = "VAN-CANCEL-INQUIRY-0001";
    private static final String CANCEL_APPROVAL_NO = "CANCEL-APPROVAL-0001";

    private PaymentCancelInquiryTransactionService transactionService;
    private PaymentCancelRepository cancelRepository;

    @BeforeEach
    void setUp() {
        cancelRepository = mock(PaymentCancelRepository.class);
        transactionService = new PaymentCancelInquiryTransactionService(cancelRepository);
    }

    @Test
    @DisplayName("UNKNOWN_TIMEOUT + VAN CANCELLED이고 update 성공이면 DB updated row 기준 CANCELLED 응답을 반환한다")
    void finalizeResolvedInquiry_cancelledAndUpdateSuccess_shouldReturnUpdatedCancelled() {
        PaymentCancel unknownTimeout = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        PaymentCancel updated = cancel(CancelStatus.CANCELLED, CANCEL_APPROVAL_NO, null);

        when(cancelRepository.updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(updated));

        CancelResponse response = transactionService.finalizeResolvedInquiry(
                unknownTimeout,
                vanCancelledResponse()
        );

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals(CANCEL_APPROVAL_NO, response.cancelApprovalNo());

        ArgumentCaptor<CancelResultUpdateParam> captor =
                ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(cancelRepository).updateUnknownTimeoutToFinal(captor.capture());
        assertEquals(CancelStatus.CANCELLED, captor.getValue().cancelStatus());
        assertEquals(VAN_CANCEL_TRX_ID, captor.getValue().vanCancelTrxId());
        assertEquals(CANCEL_APPROVAL_NO, captor.getValue().cancelApprovalNo());
        verify(cancelRepository, never()).findByPosTrx(CANCEL_POS_TRX);
    }

    @Test
    @DisplayName("UNKNOWN_TIMEOUT + VAN CANCEL_DECLINED이고 update 성공이면 DB updated row 기준 CANCEL_DECLINED 응답을 반환한다")
    void finalizeResolvedInquiry_declinedAndUpdateSuccess_shouldReturnUpdatedDeclined() {
        PaymentCancel unknownTimeout = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        PaymentCancel updated = cancel(CancelStatus.CANCEL_DECLINED, null, VanDeclineCode.DO_NOT_HONOR.code());

        when(cancelRepository.updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(updated));

        CancelResponse response = transactionService.finalizeResolvedInquiry(
                unknownTimeout,
                vanCancelDeclinedResponse()
        );

        assertEquals(CancelResultStatus.CANCEL_DECLINED, response.cancelStatus());
        assertEquals(VanDeclineCode.DO_NOT_HONOR.code(), response.declineCode());

        ArgumentCaptor<CancelResultUpdateParam> captor =
                ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(cancelRepository).updateUnknownTimeoutToFinal(captor.capture());
        assertEquals(CancelStatus.CANCEL_DECLINED, captor.getValue().cancelStatus());
        assertEquals(VAN_CANCEL_TRX_ID, captor.getValue().vanCancelTrxId());
        assertEquals(VanDeclineCode.DO_NOT_HONOR.code(), captor.getValue().declineCode());
        verify(cancelRepository, never()).findByPosTrx(CANCEL_POS_TRX);
    }

    @Test
    @DisplayName("update miss면 posTrx로 재조회하고 이미 CANCELLED면 DB reread 기준 CANCELLED 응답을 반환한다")
    void finalizeResolvedInquiry_updateMissAndRereadCancelled_shouldReturnRereadCancelled() {
        PaymentCancel unknownTimeout = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        PaymentCancel rereadCancelled = cancel(CancelStatus.CANCELLED, CANCEL_APPROVAL_NO, null);

        when(cancelRepository.updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(rereadCancelled));

        CancelResponse response = transactionService.finalizeResolvedInquiry(
                unknownTimeout,
                vanCancelledResponse()
        );

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals(CANCEL_APPROVAL_NO, response.cancelApprovalNo());
        verify(cancelRepository).updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class));
        verify(cancelRepository).findByPosTrx(CANCEL_POS_TRX);
    }

    @Test
    @DisplayName("update miss면 posTrx로 재조회하고 이미 CANCEL_DECLINED면 DB reread 기준 CANCEL_DECLINED 응답을 반환한다")
    void finalizeResolvedInquiry_updateMissAndRereadDeclined_shouldReturnRereadDeclined() {
        PaymentCancel unknownTimeout = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        PaymentCancel rereadDeclined = cancel(CancelStatus.CANCEL_DECLINED, null, VanDeclineCode.DO_NOT_HONOR.code());

        when(cancelRepository.updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(rereadDeclined));

        CancelResponse response = transactionService.finalizeResolvedInquiry(
                unknownTimeout,
                vanCancelDeclinedResponse()
        );

        assertEquals(CancelResultStatus.CANCEL_DECLINED, response.cancelStatus());
        assertEquals(VanDeclineCode.DO_NOT_HONOR.code(), response.declineCode());
        verify(cancelRepository).updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class));
        verify(cancelRepository).findByPosTrx(CANCEL_POS_TRX);
    }

    @Test
    @DisplayName("update miss 후 reread row가 없으면 RETRY_LATER 방어 응답을 반환한다")
    void finalizeResolvedInquiry_updateMissAndRereadEmpty_shouldReturnRetryLater() {
        PaymentCancel unknownTimeout = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");

        when(cancelRepository.updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.empty());

        CancelResponse response = transactionService.finalizeResolvedInquiry(
                unknownTimeout,
                vanCancelledResponse()
        );

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        verify(cancelRepository).updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class));
        verify(cancelRepository).findByPosTrx(CANCEL_POS_TRX);
    }

    @Test
    @DisplayName("cancel이 null이면 기존 정책대로 INTERNAL_ERROR 예외를 던진다")
    void finalizeResolvedInquiry_nullCancel_shouldThrowInternalError() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.finalizeResolvedInquiry(null, vanCancelledResponse())
        );

        assertEquals(ResultCode.INTERNAL_ERROR, exception.getResultCode());
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("cancel이 UNKNOWN_TIMEOUT이 아니면 기존 정책대로 INTERNAL_ERROR 예외를 던진다")
    void finalizeResolvedInquiry_notUnknownTimeoutCancel_shouldThrowInternalError() {
        PaymentCancel cancel = cancel(CancelStatus.CANCELLED, CANCEL_APPROVAL_NO, null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.finalizeResolvedInquiry(cancel, vanCancelledResponse())
        );

        assertEquals(ResultCode.INTERNAL_ERROR, exception.getResultCode());
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("VAN resultCode가 SUCCESS가 아니면 기존 정책대로 INTERNAL_ERROR 예외를 던진다")
    void finalizeResolvedInquiry_invalidResultCode_shouldThrowInternalError() {
        PaymentCancel cancel = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.finalizeResolvedInquiry(cancel, vanNotFoundResponse())
        );

        assertEquals(ResultCode.INTERNAL_ERROR, exception.getResultCode());
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("VAN targetType이 CANCEL이 아니면 기존 정책대로 INTERNAL_ERROR 예외를 던진다")
    void finalizeResolvedInquiry_invalidTargetType_shouldThrowInternalError() {
        PaymentCancel cancel = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        VanInquiryResponse response = baseVanResponse()
                .targetType(VanInquiryTargetType.APPROVAL)
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.CANCELLED)
                .vanTrxId(VAN_CANCEL_TRX_ID)
                .cancelApprovalNo(CANCEL_APPROVAL_NO)
                .message("CANCELLED")
                .build();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.finalizeResolvedInquiry(cancel, response)
        );

        assertEquals(ResultCode.INTERNAL_ERROR, exception.getResultCode());
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("VAN targetTrx가 cancel posTrx와 다르면 기존 정책대로 INTERNAL_ERROR 예외를 던진다")
    void finalizeResolvedInquiry_invalidTargetTrx_shouldThrowInternalError() {
        PaymentCancel cancel = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        VanInquiryResponse response = baseVanResponse()
                .targetTrxNo("OTHER-CANCEL-POS-TRX")
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.CANCELLED)
                .vanTrxId(VAN_CANCEL_TRX_ID)
                .cancelApprovalNo(CANCEL_APPROVAL_NO)
                .message("CANCELLED")
                .build();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.finalizeResolvedInquiry(cancel, response)
        );

        assertEquals(ResultCode.INTERNAL_ERROR, exception.getResultCode());
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
    }

    @Test
    @DisplayName("VAN status가 취소 최종 상태가 아니면 기존 정책대로 INTERNAL_ERROR 예외를 던진다")
    void finalizeResolvedInquiry_invalidStatus_shouldThrowInternalError() {
        PaymentCancel cancel = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        VanInquiryResponse response = baseVanResponse()
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.APPROVED)
                .vanTrxId(VAN_CANCEL_TRX_ID)
                .approvalNo("APPROVAL-0001")
                .message("APPROVED")
                .build();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.finalizeResolvedInquiry(cancel, response)
        );

        assertEquals(ResultCode.INTERNAL_ERROR, exception.getResultCode());
        verify(cancelRepository, never()).updateUnknownTimeoutToFinal(any());
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

    private VanInquiryResponse vanCancelledResponse() {
        return baseVanResponse()
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.CANCELLED)
                .vanTrxId(VAN_CANCEL_TRX_ID)
                .approvalNo(null)
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
                .approvalNo(null)
                .cancelApprovalNo(null)
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .message("CANCEL_DECLINED")
                .build();
    }

    private VanInquiryResponse vanNotFoundResponse() {
        return baseVanResponse()
                .resultCode(VanInquiryResultCode.NOT_FOUND)
                .status(null)
                .vanTrxId(null)
                .approvalNo(null)
                .cancelApprovalNo(null)
                .declineCode(null)
                .message("NOT_FOUND")
                .build();
    }

    private VanInquiryResponse.VanInquiryResponseBuilder baseVanResponse() {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(CANCEL_POS_TRX)
                .targetAttemptSeq(null)
                .respondedAt(LocalDateTime.now());
    }

}
