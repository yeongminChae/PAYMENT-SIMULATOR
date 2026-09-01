package com.chaeyeongmin.payment_sim.api.payment.service.transaction;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelResponseFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentCancelPrepareResult;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.domain.policy.cancel.CancelCardVerificationPolicy;
import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentCancelTransactionServiceTest {

    private static final CardFingerprintPolicy CARD_FINGERPRINT_POLICY =
            new CardFingerprintPolicy("card-fingerprint-test-secret-key");
    private static final CancelCardVerificationPolicy CANCEL_CARD_VERIFICATION_POLICY =
            new CancelCardVerificationPolicy(CARD_FINGERPRINT_POLICY);

    private PaymentCancelTransactionService transactionService;
    private PaymentCancelRepository repository;
    private PaymentAttemptRepository paymentAttemptRepository;
    private PaymentEventLogRecorder paymentEventLogRecorder;

    private CancelRequest baseReq;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentCancelRepository.class);
        paymentAttemptRepository = mock(PaymentAttemptRepository.class);
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);

        transactionService = new PaymentCancelTransactionService(
                repository,
                paymentAttemptRepository,
                CANCEL_CARD_VERIFICATION_POLICY,
                new CancelResponseFactory(),
                new CancelEventRecorder(paymentEventLogRecorder)
        );

        when(paymentAttemptRepository.acquireExistingPosTrxLock(anyString()))
                .thenReturn(Optional.of(0));

        baseReq = new CancelRequest(
                "2376-20260519-9991-2001",
                "2376-20260519-9991-1001",
                1,
                "4242424242424242"
        );
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-002
     */
    @Test
    @DisplayName("원승인 거래가 없으면 NOT_FOUND 예외를 던지고 취소를 진행하지 않는다")
    void prepare_C3_originalAttemptNotFound_shouldThrowNotFound_withoutCancelRow() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty());

        BusinessException exception =
                assertThrows(BusinessException.class, () -> transactionService.prepare(baseReq));

        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());
        verify(paymentAttemptRepository).findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq);
        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
        verify(repository, never()).insertPendingCancel(any());
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-003
     */
    @Test
    @DisplayName("원거래가 승인 상태가 아니면 취소 불가 응답을 반환하고 취소 row를 만들지 않는다")
    void prepare_C4_originalNotApproved_shouldReturnCancelNotAllowed_withoutCancelRow() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalDeclinedAttempt()));

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertThat(prepared.isCompleted()).isTrue();
        assertEquals(CancelResultStatus.CANCEL_NOT_ALLOWED, prepared.completedResponse().cancelStatus());
        assertEquals("ORIGINAL_NOT_APPROVED", prepared.completedResponse().declineCode());

        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
        verify(repository, never()).insertPendingCancel(any());
    }

    @Test
    @DisplayName("원거래가 승인 상태가 아니면 CANCEL_NOT_ALLOWED 이벤트를 기록한다")
    void prepare_originalNotApproved_shouldLogCancelNotAllowed() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3003",
                "2376-20260521-9991-1003",
                1,
                "4242424242424242"
        );

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalDeclinedAttempt()));

        PaymentCancelPrepareResult prepared = transactionService.prepare(request);

        assertEquals(CancelResultStatus.CANCEL_NOT_ALLOWED, prepared.completedResponse().cancelStatus());
        verify(paymentEventLogRecorder).record(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_NOT_ALLOWED
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && ResultCode.CANCEL_NOT_ALLOWED.name().equals(event.resultCode())
                        && PaymentFinalStatus.DECLINED.name().equals(event.statusSnapshot())
                        && "ORIGINAL_NOT_APPROVED".equals(event.declineCode())
                        && "original attempt is not approved".equals(event.note())
        ));
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-004
     */
    @Test
    @DisplayName("기존 취소가 PENDING이면 재시도 응답을 반환하고 PENDING row를 추가하지 않는다")
    void prepare_C4_existingPending_shouldReturnRetryLater_withoutInsert() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(pendingCancel()));

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.RETRY_LATER, prepared.completedResponse().cancelStatus());
        verify(repository, never()).insertPendingCancel(any());
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-005
     */
    @Test
    @DisplayName("기존 취소가 CANCELLED이면 이미 취소된 결과를 반환하고 PENDING row를 추가하지 않는다")
    void prepare_C4_existingCancelled_shouldReturnDbCancelled_withoutInsert() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(cancelledCancel()));

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.ALREADY_CANCELLED, prepared.completedResponse().cancelStatus());
        assertEquals("A137515458", prepared.completedResponse().cancelApprovalNo());
        verify(repository, never()).insertPendingCancel(any());
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-006
     */
    @Test
    @DisplayName("기존 취소가 CANCEL_DECLINED이면 저장된 거절 결과를 반환하고 PENDING row를 추가하지 않는다")
    void prepare_C4_existingCancelDeclined_shouldReturnDbDeclined_withoutInsert() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(cancelDeclinedCancel()));

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.CANCEL_DECLINED, prepared.completedResponse().cancelStatus());
        assertEquals("05", prepared.completedResponse().declineCode());
        verify(repository, never()).insertPendingCancel(any());
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-010
     */
    @Test
    @DisplayName("C5 insert miss면 기존 취소를 재조회하고 재조회된 DB 응답을 반환한다")
    void prepare_C5_insertMiss_thenRereadExistingCancel_shouldReturnDbResponse() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();
        PaymentCancel rereadCancel = cancelledCancel();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(rereadCancel));
        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenReturn(Optional.empty());

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.ALREADY_CANCELLED, prepared.completedResponse().cancelStatus());
        assertEquals(rereadCancel.cancelApprovalNo(), prepared.completedResponse().cancelApprovalNo());

        verify(repository, times(2))
                .findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
        verify(repository).insertPendingCancel(any(CancelInsertParam.class));
    }

    @Test
    @DisplayName("C5 insert conflict 후 기존 PENDING row가 보이면 RETRY_LATER를 반환한다")
    void prepare_C5_insertConflict_existingPending_shouldReturnRetryLater() {
        givenApprovedOriginal(baseReq);
        givenNoExistingCancelThenReread(Optional.of(pendingCancel()));
        givenInsertPendingConflict();

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.RETRY_LATER, prepared.completedResponse().cancelStatus());
        verifyC5ConflictRecoveryTried(baseReq);
    }

    @Test
    @DisplayName("C5 insert conflict 후 기존 CANCELLED row가 보이면 ALREADY_CANCELLED를 반환한다")
    void prepare_C5_insertConflict_existingCancelled_shouldReturnAlreadyCancelled() {
        PaymentCancel existingCancel = cancelledCancel();

        givenApprovedOriginal(baseReq);
        givenNoExistingCancelThenReread(Optional.of(existingCancel));
        givenInsertPendingConflict();

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.ALREADY_CANCELLED, prepared.completedResponse().cancelStatus());
        assertEquals(existingCancel.cancelApprovalNo(), prepared.completedResponse().cancelApprovalNo());
        verifyC5ConflictRecoveryTried(baseReq);
    }

    @Test
    @DisplayName("C5 insert conflict 후 기존 CANCEL_DECLINED row가 보이면 CANCEL_DECLINED를 반환한다")
    void prepare_C5_insertConflict_existingDeclined_shouldReturnDeclined() {
        PaymentCancel existingCancel = cancelDeclinedCancel();

        givenApprovedOriginal(baseReq);
        givenNoExistingCancelThenReread(Optional.of(existingCancel));
        givenInsertPendingConflict();

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.CANCEL_DECLINED, prepared.completedResponse().cancelStatus());
        assertEquals(existingCancel.declineCode(), prepared.completedResponse().declineCode());
        verifyC5ConflictRecoveryTried(baseReq);
    }

    @Test
    @DisplayName("C5 insert conflict 후 재조회도 empty면 RETRY_LATER를 반환한다")
    void prepare_C5_insertConflict_rereadEmpty_shouldReturnRetryLater() {
        givenApprovedOriginal(baseReq);
        givenNoExistingCancelThenReread(Optional.empty());
        givenInsertPendingConflict();

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertEquals(CancelResultStatus.RETRY_LATER, prepared.completedResponse().cancelStatus());
        verifyC5ConflictRecoveryTried(baseReq);
    }

    @Test
    @DisplayName("같은 cancel posTrx가 있으면 원거래 조회 전에 CONFLICT를 반환한다")
    void prepare_sameCancelPosTrxExists_shouldThrowConflict_beforeOriginalLookup() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3001",
                "2376-20260521-9991-1001",
                1,
                "4242424242424242"
        );
        PaymentCancel existing = paymentCancel(
                request,
                CancelStatus.CANCELLED,
                "C123456789",
                null
        );

        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.prepare(request)
        );

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        assertEquals("POS_TRX_ALREADY_USED", exception.getMessage());
        verifySameCancelPosTrxConflictBlocked(request);
        verify(paymentEventLogRecorder).recordAfterRollback(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_CONFLICT
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && ResultCode.CONFLICT.name().equals(event.resultCode())
                        && CancelStatus.CANCELLED.name().equals(event.statusSnapshot())
                        && "C123456789".equals(event.approvalNo())
                        && "POS_TRX_ALREADY_USED".equals(event.note())
        ));
    }

    @Test
    @DisplayName("이미 사용된 cancel posTrx와 카드 불일치가 함께 있으면 posTrx 충돌을 우선한다")
    void prepare_usedCancelPosTrxWithDifferentCard_shouldPrioritizePosTrxConflict() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3001",
                "2376-20260521-9991-1001",
                1,
                "4111111111111111"
        );
        PaymentCancel existing = paymentCancel(
                request,
                CancelStatus.CANCELLED,
                "C123456789",
                null
        );

        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.prepare(request)
        );

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        assertEquals("POS_TRX_ALREADY_USED", exception.getMessage());
        verifySameCancelPosTrxConflictBlocked(request);
    }

    @Test
    @DisplayName("같은 cancel posTrx를 다른 original에 재사용하면 원거래 조회 전에 CONFLICT를 반환한다")
    void prepare_sameCancelPosTrxDifferentOriginal_shouldThrowConflict_beforeOriginalLookup() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3001",
                "2376-20260521-9991-1002",
                1,
                "4242424242424242"
        );
        PaymentCancel existing = new PaymentCancel(
                request.posTrx(),
                "2376-20260521-9991-1001",
                1,
                CancelStatus.CANCELLED,
                "C998855",
                null
        );

        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.prepare(request)
        );

        assertEquals(ResultCode.CONFLICT, exception.getResultCode());
        assertEquals("POS_TRX_ALREADY_USED", exception.getMessage());
        verifySameCancelPosTrxConflictBlocked(request);
        verify(paymentEventLogRecorder).recordAfterRollback(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_CONFLICT
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && ResultCode.CONFLICT.name().equals(event.resultCode())
                        && CancelStatus.CANCELLED.name().equals(event.statusSnapshot())
                        && "C998855".equals(event.approvalNo())
                        && "POS_TRX_ALREADY_USED".equals(event.note())
        ));
    }

    /**
     * [UT_ID] UT-2.1-FP-007 / UT-2.1-FP-008
     */
    @Test
    @DisplayName("취소 카드 fingerprint가 원승인과 다르면 취소 row 생성을 차단한다")
    void prepare_C4_cardFingerprintMismatch_shouldThrowCancelNotAllowed_withoutCancelRow() {
        CancelRequest request = new CancelRequest(
                "2376-20260519-9991-2001-01",
                "2376-20260519-9991-1001-01",
                1,
                "4111111111111111"
        );

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalApprovedAttempt()));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.prepare(request)
        );

        assertThat(exception.getResultCode()).isEqualTo(ResultCode.CANCEL_NOT_ALLOWED);
        assertThat(exception.getMessage()).isEqualTo("CARD_MISMATCH");

        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(anyString(), anyInt());
        verify(repository, never()).insertPendingCancel(any());
    }

    /**
     * [UT_ID] UT-2.1-FP-006
     */
    @Test
    @DisplayName("BIN8과 last4가 같아도 fingerprint가 다르면 CARD_MISMATCH로 취소를 거절한다")
    void prepare_sameBinAndLast4ButDifferentFingerprint_shouldThrowCardMismatch() {
        String cancelCardNo = "4242424211114242";
        PaymentAttempt originalAttempt = originalApprovedAttempt();

        assertThat(cancelCardNo.substring(0, 8)).isEqualTo(originalAttempt.cardBin());
        assertThat(cancelCardNo.substring(cancelCardNo.length() - 4)).isEqualTo(originalAttempt.cardLast4());
        assertThat(CARD_FINGERPRINT_POLICY.generate(cancelCardNo))
                .isNotEqualTo(originalAttempt.cardFingerprint());

        CancelRequest request = new CancelRequest(
                "2376-20260519-9991-2001-02",
                "2376-20260519-9991-1001-01",
                originalAttempt.attemptSeq(),
                cancelCardNo
        );

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalAttempt));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.prepare(request)
        );

        assertThat(exception.getResultCode()).isEqualTo(ResultCode.CANCEL_NOT_ALLOWED);
        assertThat(exception.getMessage()).isEqualTo("CARD_MISMATCH");
    }

    @Test
    @DisplayName("legacy 원승인에 fingerprint가 없으면 BIN8과 last4가 같은 카드로 취소 준비를 완료한다")
    void prepare_legacyOriginalWithoutFingerprint_sameBinAndLast4_shouldCreatePending() {
        PaymentAttempt legacyOriginalAttempt = originalApprovedAttemptWithoutFingerprint();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq()))
                .thenReturn(Optional.of(legacyOriginalAttempt));
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(baseReq.originalPosTrx(), baseReq.originalAttemptSeq()))
                .thenReturn(Optional.empty());
        when(repository.insertPendingCancel(any()))
                .thenReturn(Optional.of(pendingCancel()));

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertFalse(prepared.isCompleted());
        assertEquals(baseReq.posTrx(), prepared.posTrx());
        assertEquals(baseReq.originalPosTrx(), prepared.originalPosTrx());
        assertEquals(baseReq.originalAttemptSeq(), prepared.originalAttemptSeq());
        assertThat(prepared.originalAttempt()).isEqualTo(legacyOriginalAttempt);
        verify(repository).insertPendingCancel(any());
    }

    @Test
    @DisplayName("legacy 원승인에 fingerprint가 없고 BIN8 또는 last4가 다르면 CARD_MISMATCH로 거절한다")
    void prepare_legacyOriginalWithoutFingerprint_differentBinOrLast4_shouldThrowCardMismatch() {
        PaymentAttempt legacyOriginalAttempt = originalApprovedAttemptWithoutFingerprint();
        CancelRequest request = new CancelRequest(
                "2376-20260519-9991-2001-03",
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq(),
                "4111111111111111"
        );

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(legacyOriginalAttempt));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> transactionService.prepare(request)
        );

        assertThat(exception.getResultCode()).isEqualTo(ResultCode.CANCEL_NOT_ALLOWED);
        assertThat(exception.getMessage()).isEqualTo("CARD_MISMATCH");
        verify(repository, never()).insertPendingCancel(any());
    }

    /**
     * [UT_ID] UT-2.1-FP-005
     */
    @Test
    @DisplayName("원승인 카드와 취소 요청 카드가 같으면 PENDING row를 만들고 VAN 호출 준비 결과를 반환한다")
    void prepare_sameCard_shouldCreatePendingAndReturnCreated() {
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();
        PaymentAttempt originalAttempt = originalApprovedAttempt();

        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalAttempt));
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty());
        when(repository.insertPendingCancel(any()))
                .thenReturn(Optional.of(pendingCancel()));

        PaymentCancelPrepareResult prepared = transactionService.prepare(baseReq);

        assertFalse(prepared.isCompleted());
        assertEquals(baseReq.posTrx(), prepared.posTrx());
        assertEquals(baseReq.originalPosTrx(), prepared.originalPosTrx());
        assertEquals(baseReq.originalAttemptSeq(), prepared.originalAttemptSeq());
        assertThat(prepared.originalAttempt()).isEqualTo(originalAttempt);
        verify(repository).insertPendingCancel(any());
    }

    @Test
    @DisplayName("신규 취소 준비가 성공하면 PENDING 생성 이벤트를 기록한다")
    void prepare_newRequest_shouldLogPendingCreatedEvent() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3004",
                "2376-20260521-9991-1004",
                1,
                "4242424242424242"
        );

        givenNoCurrentCancelAndApprovedOriginal(request);
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                request.originalPosTrx(),
                request.originalAttemptSeq()
        )).thenReturn(Optional.empty());
        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenReturn(Optional.of(paymentCancel(
                        request,
                        CancelStatus.PENDING,
                        null,
                        null
                )));

        PaymentCancelPrepareResult prepared = transactionService.prepare(request);

        assertFalse(prepared.isCompleted());
        verify(paymentEventLogRecorder).record(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_PENDING_CREATED
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && event.resultCode() == null
                        && CancelStatus.PENDING.name().equals(event.statusSnapshot())
                        && "cancel pending created".equals(event.note())
        ));
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

    private PaymentCancelPrepareResult createdPrepareResult() {
        return PaymentCancelPrepareResult.created(
                baseReq.posTrx(),
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq(),
                originalApprovedAttempt()
        );
    }

    private void givenApprovedOriginal(CancelRequest request) {
        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalApprovedAttempt()));
    }

    private void givenNoCurrentCancelAndApprovedOriginal(CancelRequest request) {
        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.empty());
        givenApprovedOriginal(request);
    }

    private void givenNoExistingCancelThenReread(Optional<PaymentCancel> rereadCancel) {
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq()
        ))
                .thenReturn(Optional.empty())
                .thenReturn(rereadCancel);
    }

    private void givenInsertPendingConflict() {
        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
    }

    private void verifyC5ConflictRecoveryTried(CancelRequest request) {
        verify(repository, times(2)).findByOriginalPosTrxAndOriginalAttemptSeq(
                request.originalPosTrx(),
                request.originalAttemptSeq()
        );
        verify(repository).insertPendingCancel(any(CancelInsertParam.class));
    }

    private void verifySameCancelPosTrxConflictBlocked(CancelRequest request) {
        verify(repository).findByPosTrx(request.posTrx());
        verify(paymentAttemptRepository, never()).findByPosTrxAndAttemptSeq(anyString(), anyInt());
        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(anyString(), anyInt());
        verify(repository, never()).insertPendingCancel(any(CancelInsertParam.class));
        verify(repository, never()).updateCancelResult(any(CancelResultUpdateParam.class));
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

    private PaymentAttempt originalApprovedAttemptWithoutFingerprint() {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A207076083",
                null,
                "42424242",
                "4242",
                "VISA",
                null,
                1,
                10000,
                "2376-20260519-9991-1001-01"
        );
    }

    private PaymentAttempt originalDeclinedAttempt() {
        return new PaymentAttempt(
                PaymentFinalStatus.DECLINED.name(),
                null,
                "05",
                "41111111",
                "1111",
                "VISA",
                CARD_FINGERPRINT_POLICY.generate("4111111111111111"),
                1,
                10000,
                "2376-20260519-9991-1002-01"
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
