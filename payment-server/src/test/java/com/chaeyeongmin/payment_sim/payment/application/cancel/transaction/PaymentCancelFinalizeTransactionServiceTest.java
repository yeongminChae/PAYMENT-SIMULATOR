package com.chaeyeongmin.payment_sim.payment.application.cancel.transaction;

import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResultStatus;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelRequest;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.application.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.payment.application.cancel.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.payment.application.cancel.support.CancelResponseFactory;
import com.chaeyeongmin.payment_sim.payment.application.cancel.transaction.model.PaymentCancelPrepareResult;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.PaymentCancel;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import com.chaeyeongmin.payment_sim.payment.domain.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCancelFinalizeTransactionServiceTest {

    private static final CardFingerprintPolicy CARD_FINGERPRINT_POLICY =
            new CardFingerprintPolicy("card-fingerprint-test-secret-key");
    private PaymentCancelFinalizeTransactionService transactionService;
    private PaymentCancelRepository repository;
    private PaymentEventLogRecorder paymentEventLogRecorder;

    private CancelRequest baseReq;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentCancelRepository.class);
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);

        transactionService = new PaymentCancelFinalizeTransactionService(
                repository,
                new CancelResponseFactory(),
                new CancelEventRecorder(paymentEventLogRecorder)
        );

        baseReq = new CancelRequest(
                "2376-20260519-9991-2001",
                "2376-20260519-9991-1001",
                1,
                "4242424242424242"
        );
    }
    /**
     * [UT_ID] UT-PAYMENT-CANCEL-007
     */
    @Test
    @DisplayName("VAN 취소 성공 결과를 받으면 CANCELLED 결과를 저장하고 반환한다")
    void finalizeCancel_vanCancelled_updateSuccess_shouldReturnCancelled_C8() {
        PaymentCancelPrepareResult prepared = createdPrepareResult();

        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(cancelledCancel()));

        CancelResponse response = transactionService.finalizeCancel(prepared, vanCancelResCancelled());

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals(cancelledCancel().cancelApprovalNo(), response.cancelApprovalNo());

        ArgumentCaptor<CancelResultUpdateParam> captor = ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(repository).updateCancelResult(captor.capture());
        assertEquals(CancelStatus.CANCELLED, captor.getValue().cancelStatus());
        assertEquals("2376-20260519-9991-1001-01", captor.getValue().vanCancelTrxId());
        assertEquals("VAN-CANCEL-APPROVAL-0001", captor.getValue().cancelApprovalNo());
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-008
     */
    @Test
    @DisplayName("VAN 취소 거절 결과를 받으면 CANCEL_DECLINED 결과를 저장하고 반환한다")
    void finalizeCancel_vanDeclined_updateSuccess_shouldReturnDeclined_C8() {
        PaymentCancelPrepareResult prepared = createdPrepareResult();
        PaymentCancel updatedCancel = cancelDeclinedCancel();

        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(updatedCancel));

        CancelResponse response = transactionService.finalizeCancel(prepared, vanCancelResDeclined());

        assertEquals(CancelResultStatus.CANCEL_DECLINED, response.cancelStatus());
        assertEquals(updatedCancel.declineCode(), response.declineCode());

        ArgumentCaptor<CancelResultUpdateParam> captor = ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(repository).updateCancelResult(captor.capture());
        assertEquals(CancelStatus.CANCEL_DECLINED, captor.getValue().cancelStatus());
        assertEquals("2376-20260519-9991-1001-01", captor.getValue().vanCancelTrxId());
        assertEquals(VanDeclineCode.DO_NOT_HONOR.code(), captor.getValue().declineCode());
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-009
     */
    @Test
    @DisplayName("VAN 취소 결과가 PENDING이면 결과를 확정하지 않고 재시도 응답을 반환한다")
    void finalizeCancel_vanPending_shouldReturnRetryLater_withoutUpdate_C8() {
        CancelResponse response = transactionService.finalizeCancel(createdPrepareResult(), vanCancelResPending());

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        verify(repository, never()).updateCancelResult(any());
    }

    @Test
    @DisplayName("C7 update miss면 original 기준 재조회 결과로 복구 응답을 반환한다")
    void finalizeCancel_C7_updateMiss_thenRereadExistingCancel_shouldReturnRecoveredDbResponse() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();
        PaymentCancel rereadCancel = cancelledCancel();

        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(rereadCancel));

        CancelResponse response = transactionService.finalizeCancel(createdPrepareResult(), vanCancelResCancelled());

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals(rereadCancel.cancelApprovalNo(), response.cancelApprovalNo());

        verify(repository).updateCancelResult(any(CancelResultUpdateParam.class));
        verify(repository).findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
    }

    @Test
    @DisplayName("C7 update miss 후 재조회 결과가 PENDING이면 RETRY_LATER를 반환한다")
    void finalizeCancel_C7_updateMiss_rereadPending_shouldReturnRetryLater() {
        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq()))
                .thenReturn(Optional.of(pendingCancel()));

        CancelResponse response = transactionService.finalizeCancel(createdPrepareResult(), vanCancelResCancelled());

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
        verify(repository).updateCancelResult(any(CancelResultUpdateParam.class));
        verify(repository).findByOriginalPosTrxAndOriginalAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq());
    }

    @Test
    @DisplayName("C7 update miss 후 재조회 결과가 CANCEL_DECLINED이면 CANCEL_DECLINED를 반환한다")
    void finalizeCancel_C7_updateMiss_rereadDeclined_shouldReturnCancelDeclined() {
        PaymentCancel recoveredCancel = cancelDeclinedCancel();

        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq()))
                .thenReturn(Optional.of(recoveredCancel));

        CancelResponse response = transactionService.finalizeCancel(createdPrepareResult(), vanCancelResDeclined());

        assertEquals(CancelResultStatus.CANCEL_DECLINED, response.cancelStatus());
        assertEquals(recoveredCancel.declineCode(), response.declineCode());
        verify(repository).updateCancelResult(any(CancelResultUpdateParam.class));
        verify(repository).findByOriginalPosTrxAndOriginalAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq());
    }

    @Test
    @DisplayName("C7 update miss 후 재조회도 empty면 RETRY_LATER를 반환한다")
    void finalizeCancel_C7_updateMiss_rereadEmpty_shouldReturnRetryLater() {
        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq()))
                .thenReturn(Optional.empty());

        CancelResponse response = transactionService.finalizeCancel(createdPrepareResult(), vanCancelResCancelled());

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        assertThat(response.cancelApprovalNo()).isNull();
        assertThat(response.declineCode()).isNull();
        verify(repository).updateCancelResult(any(CancelResultUpdateParam.class));
        verify(repository).findByOriginalPosTrxAndOriginalAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq());
    }

    @Test
    @DisplayName("취소 성공 확정 시 CANCEL_FINALIZED 이벤트를 기록한다")
    void finalizeCancel_vanCancelled_shouldLogFinalizedEvent() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3004",
                "2376-20260521-9991-1004",
                1,
                "4242424242424242"
        );
        PaymentCancelPrepareResult prepared = PaymentCancelPrepareResult.created(
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                originalApprovedAttempt()
        );
        PaymentCancel cancelledCancel = paymentCancel(
                request,
                CancelStatus.CANCELLED,
                "C777777777",
                null
        );

        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(cancelledCancel));

        CancelResponse response = transactionService.finalizeCancel(
                prepared,
                vanCancelResCancelled(request, "C777777777")
        );

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        verify(paymentEventLogRecorder).record(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_FINALIZED
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && ResultCode.OK.name().equals(event.resultCode())
                        && CancelStatus.CANCELLED.name().equals(event.statusSnapshot())
                        && "C777777777".equals(event.approvalNo())
                        && "cancel finalized".equals(event.note())
        ));
    }

    @Test
    @DisplayName("VAN timeout이면 PENDING 취소 row를 UNKNOWN_TIMEOUT으로 확정하고 RETRY_LATER를 반환한다")
    void finalizeUnknownTimeout_updateSuccess_shouldReturnRetryLater() {
        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(unknownTimeoutCancel()));

        CancelResponse response = transactionService.finalizeUnknownTimeout(createdPrepareResult());

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());

        ArgumentCaptor<CancelResultUpdateParam> captor = ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(repository).updateCancelResult(captor.capture());
        assertEquals(CancelStatus.UNKNOWN_TIMEOUT, captor.getValue().cancelStatus());
        assertEquals("TIMEOUT", captor.getValue().declineCode());
        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(anyString(), anyInt());
    }

    private PaymentCancelPrepareResult createdPrepareResult() {
        return PaymentCancelPrepareResult.created(
                baseReq.posTrx(),
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq(),
                originalApprovedAttempt()
        );
    }
    private CancelRequest cancelRequest(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String cardNo
    ) {
        return new CancelRequest(posTrx, originalPosTrx, originalAttemptSeq, cardNo);
    }

    private PaymentAttempt originalApprovedAttempt() {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A207076083",
                null,
                "42424242",
                "4242",
                "VISA",
                CARD_FINGERPRINT_POLICY.generate("4242424242424242"),
                1,
                10000,
                "2376-20260519-9991-1001-01"
        );
    }

    private PaymentCancel pendingCancel() {
        return paymentCancel(CancelStatus.PENDING, null, null);
    }

    private PaymentCancel cancelledCancel() {
        return paymentCancel(CancelStatus.CANCELLED, "A137515458", null);
    }

    private PaymentCancel cancelDeclinedCancel() {
        return paymentCancel(CancelStatus.CANCEL_DECLINED, null, "05");
    }

    private PaymentCancel unknownTimeoutCancel() {
        return paymentCancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
    }

    private PaymentCancel paymentCancel(
            CancelStatus status,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new PaymentCancel(
                baseReq.posTrx(),
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq(),
                status,
                "VAN-CANCEL-0001",
                cancelApprovalNo,
                declineCode
        );
    }

    private PaymentCancel paymentCancel(
            CancelRequest request,
            CancelStatus status,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new PaymentCancel(
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                status,
                "VAN-CANCEL-0001",
                cancelApprovalNo,
                declineCode
        );
    }

    private VanCancelResponse vanCancelResCancelled() {
        return VanCancelResponse.builder()
                .posTrx(baseReq.posTrx())
                .originalPosTrx(baseReq.originalPosTrx())
                .originalAttemptSeq(baseReq.originalAttemptSeq())
                .cancelStatus(CancelStatus.CANCELLED)
                .cancelApprovalNo("VAN-CANCEL-APPROVAL-0001")
                .declineCode(null)
                .vanTrxId("2376-20260519-9991-1001-01")
                .message("CANCELLED_BY_VAN")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanCancelResponse vanCancelResCancelled(CancelRequest request, String cancelApprovalNo) {
        return VanCancelResponse.builder()
                .posTrx(request.posTrx())
                .originalPosTrx(request.originalPosTrx())
                .originalAttemptSeq(request.originalAttemptSeq())
                .cancelStatus(CancelStatus.CANCELLED)
                .cancelApprovalNo(cancelApprovalNo)
                .declineCode(null)
                .vanTrxId("VAN-TRX-CANCELLED")
                .message("CANCELLED")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanCancelResponse vanCancelResPending() {
        return VanCancelResponse.builder()
                .posTrx(baseReq.posTrx())
                .originalPosTrx(baseReq.originalPosTrx())
                .originalAttemptSeq(baseReq.originalAttemptSeq())
                .cancelStatus(CancelStatus.PENDING)
                .cancelApprovalNo(null)
                .declineCode(VanDeclineCode.TIMEOUT)
                .vanTrxId("2376-20260519-9991-1001-01")
                .message("CANCEL_TIMEOUT")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanCancelResponse vanCancelResDeclined() {
        return VanCancelResponse.builder()
                .posTrx(baseReq.posTrx())
                .originalPosTrx(baseReq.originalPosTrx())
                .originalAttemptSeq(baseReq.originalAttemptSeq())
                .cancelStatus(CancelStatus.CANCEL_DECLINED)
                .cancelApprovalNo(null)
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .vanTrxId("2376-20260519-9991-1001-01")
                .message("CANCEL_DECLINED_BY_VAN")
                .respondedAt(LocalDateTime.now())
                .build();
    }
}
