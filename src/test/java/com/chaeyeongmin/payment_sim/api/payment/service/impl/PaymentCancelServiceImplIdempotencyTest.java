package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.validate.CancelRequestValidator;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCancelServiceImplIdempotencyTest {

    private PaymentCancelService service;
    private PaymentCancelRepository repository;
    private VanGateway vanGateway;
    private CancelRequestValidator validator;
    private VanCancelAssembler vanCancelAssembler;
    private PaymentEventLogRecorder paymentEventLogRecorder;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentCancelRepository.class);
        vanGateway = mock(VanGateway.class);
        validator = mock(CancelRequestValidator.class);
        vanCancelAssembler = mock(VanCancelAssembler.class);
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);

        service = new PaymentCancelServiceImpl(
                repository,
                vanGateway,
                validator,
                vanCancelAssembler,
                paymentEventLogRecorder
        );
    }

    /**
     * [시나리오] UT-2-CANCEL-IDEMP-001
     * 기존 PAYMENT_CANCEL에 같은 cancel posTrx가 있으면 원거래 조회 전에 CONFLICT를 반환한다.
     *
     * Given:
     * - 기존 PAYMENT_CANCEL row가 존재한다.
     * - posTrx=2376-20260521-9991-3001이다.
     * - originalPosTrx=2376-20260521-9991-1001, originalAttemptSeq=1이다.
     * - cancelStatus=CANCELLED이다.
     *
     * When:
     * - 같은 cancel posTrx=2376-20260521-9991-3001로 취소 요청이 들어온다.
     * - originalPosTrx=2376-20260521-9991-1001, originalAttemptSeq=1이다.
     *
     * Then:
     * - BusinessException이 발생한다.
     * - ResultCode=CONFLICT, message=POS_TRX_ALREADY_USED를 검증한다.
     * - 원거래 조회, original 기준 취소 조회, insert, VAN cancel, update는 발생하지 않는다.
     */
    @Test
    void cancel_sameCancelPosTrxExists_shouldThrowConflict_beforeOriginalLookup() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3001",
                "2376-20260521-9991-1001",
                1,
                "4242424242424242"
        );
        PaymentCancel existing = paymentCancel(
                "2376-20260521-9991-3001",
                "2376-20260521-9991-1001",
                1,
                CancelStatus.CANCELLED,
                "C123456789",
                null
        );

        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.cancel(request)
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

    /**
     * [시나리오] UT-2-CANCEL-IDEMP-002
     * 다른 cancel posTrx로 같은 original을 취소 요청했는데 기존 CANCELLED row가 있으면 ALREADY_CANCELLED를 재응답한다.
     *
     * Given:
     * - 현재 cancel posTrx=2376-20260521-9991-3002는 PAYMENT_CANCEL에 존재하지 않는다.
     * - 원승인 attempt는 APPROVED 상태로 존재한다.
     * - 같은 original에 대한 기존 PAYMENT_CANCEL row가 존재한다.
     * - 기존 cancel row는 posTrx=2376-20260521-9991-3001, cancelStatus=CANCELLED, cancelApprovalNo=C123456789이다.
     *
     * When:
     * - cancel posTrx=2376-20260521-9991-3002로 같은 original 취소 요청이 들어온다.
     *
     * Then:
     * - 응답 cancelStatus=ALREADY_CANCELLED, cancelApprovalNo=C123456789를 검증한다.
     * - 응답 originalPosTrx/originalAttemptSeq를 검증한다.
     * - 신규 cancel insert, VAN cancel, update는 발생하지 않는다.
     */
    @Test
    void cancel_differentCancelPosTrx_sameOriginalExistingCancelled_shouldReturnAlreadyCancelled_withoutVan() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3002",
                "2376-20260521-9991-1001",
                1,
                "4242424242424242"
        );
        PaymentCancel existing = paymentCancel(
                "2376-20260521-9991-3001",
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                CancelStatus.CANCELLED,
                "C123456789",
                null
        );

        givenNoCurrentCancelAndApprovedOriginal(request);
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                request.originalPosTrx(),
                request.originalAttemptSeq()
        )).thenReturn(Optional.of(existing));

        CancelResponse response = service.cancel(request);

        assertEquals(CancelResultStatus.ALREADY_CANCELLED, response.cancelStatus());
        assertEquals("C123456789", response.cancelApprovalNo());
        assertEquals(request.originalPosTrx(), response.originalPosTrx());
        assertEquals(request.originalAttemptSeq(), response.originalAttemptSeq());
        verifyExistingOriginalCancelRepliedWithoutVan(request);
        verify(paymentEventLogRecorder).record(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_REUSED_BY_ORIGINAL
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && ResultCode.ALREADY_CANCELLED.name().equals(event.resultCode())
                        && CancelResultStatus.ALREADY_CANCELLED.name().equals(event.statusSnapshot())
                        && "C123456789".equals(event.approvalNo())
                        && "cancel result reused by original".equals(event.note())
        ));
    }

    /**
     * [시나리오] UT-2-CANCEL-IDEMP-003
     * 다른 cancel posTrx로 같은 original을 취소 요청했는데 기존 PENDING row가 있으면 RETRY_LATER를 재응답한다.
     *
     * Given:
     * - 현재 cancel posTrx=2376-20260521-9991-3002는 PAYMENT_CANCEL에 존재하지 않는다.
     * - 원승인 attempt는 APPROVED 상태로 존재한다.
     * - 같은 original에 대한 기존 PAYMENT_CANCEL row가 존재한다.
     * - 기존 cancel row는 posTrx=2376-20260521-9991-3001, cancelStatus=PENDING, cancelApprovalNo=null이다.
     *
     * When:
     * - cancel posTrx=2376-20260521-9991-3002로 같은 original 취소 요청이 들어온다.
     *
     * Then:
     * - 응답 cancelStatus=RETRY_LATER, cancelApprovalNo=null을 검증한다.
     * - 응답 originalPosTrx/originalAttemptSeq를 검증한다.
     * - 신규 cancel insert, VAN cancel, update는 발생하지 않는다.
     */
    @Test
    void cancel_differentCancelPosTrx_sameOriginalExistingPending_shouldReturnRetryLater_withoutVan() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3002",
                "2376-20260521-9991-1001",
                1,
                "4242424242424242"
        );
        PaymentCancel existing = paymentCancel(
                "2376-20260521-9991-3001",
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                CancelStatus.PENDING,
                null,
                null
        );

        givenNoCurrentCancelAndApprovedOriginal(request);
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                request.originalPosTrx(),
                request.originalAttemptSeq()
        )).thenReturn(Optional.of(existing));

        CancelResponse response = service.cancel(request);

        assertEquals(CancelResultStatus.RETRY_LATER, response.cancelStatus());
        assertNull(response.cancelApprovalNo());
        assertEquals(request.originalPosTrx(), response.originalPosTrx());
        assertEquals(request.originalAttemptSeq(), response.originalAttemptSeq());
        verifyExistingOriginalCancelRepliedWithoutVan(request);
        verify(paymentEventLogRecorder).record(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_REUSED_BY_ORIGINAL
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && ResultCode.RETRY_LATER.name().equals(event.resultCode())
                        && CancelResultStatus.RETRY_LATER.name().equals(event.statusSnapshot())
                        && "cancel result reused by original".equals(event.note())
        ));
    }

    /**
     * [시나리오] UT-2-CANCEL-IDEMP-004
     * 다른 cancel posTrx로 같은 original을 취소 요청했는데 기존 CANCEL_DECLINED row가 있으면 CANCEL_DECLINED를 재응답한다.
     *
     * Given:
     * - 현재 cancel posTrx=2376-20260521-9991-3002는 PAYMENT_CANCEL에 존재하지 않는다.
     * - 원승인 attempt는 APPROVED 상태로 존재한다.
     * - 같은 original에 대한 기존 PAYMENT_CANCEL row가 존재한다.
     * - 기존 cancel row는 posTrx=2376-20260521-9991-3001, cancelStatus=CANCEL_DECLINED, declineCode=CANCEL_DECLINED이다.
     *
     * When:
     * - cancel posTrx=2376-20260521-9991-3002로 같은 original 취소 요청이 들어온다.
     *
     * Then:
     * - 응답 cancelStatus=CANCEL_DECLINED, cancelApprovalNo=null, declineCode=CANCEL_DECLINED를 검증한다.
     * - 응답 originalPosTrx/originalAttemptSeq를 검증한다.
     * - 신규 cancel insert, VAN cancel, update는 발생하지 않는다.
     */
    @Test
    void cancel_differentCancelPosTrx_sameOriginalExistingCancelDeclined_shouldReturnCancelDeclined_withoutVan() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3002",
                "2376-20260521-9991-1001",
                1,
                "4242424242424242"
        );
        PaymentCancel existing = paymentCancel(
                "2376-20260521-9991-3001",
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                CancelStatus.CANCEL_DECLINED,
                null,
                "CANCEL_DECLINED"
        );

        givenNoCurrentCancelAndApprovedOriginal(request);
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                request.originalPosTrx(),
                request.originalAttemptSeq()
        )).thenReturn(Optional.of(existing));

        CancelResponse response = service.cancel(request);

        assertEquals(CancelResultStatus.CANCEL_DECLINED, response.cancelStatus());
        assertNull(response.cancelApprovalNo());
        assertEquals("CANCEL_DECLINED", response.declineCode());
        assertEquals(request.originalPosTrx(), response.originalPosTrx());
        assertEquals(request.originalAttemptSeq(), response.originalAttemptSeq());
        verifyExistingOriginalCancelRepliedWithoutVan(request);
        verify(paymentEventLogRecorder).record(argThat(event ->
                event.eventType() == PaymentEventType.CANCEL_REUSED_BY_ORIGINAL
                        && request.posTrx().equals(event.posTrx())
                        && event.currentTrxNo() == null
                        && request.originalPosTrx().equals(event.originalPosTrx())
                        && request.originalAttemptSeq() == event.originalAttemptSeq()
                        && ResultCode.CANCEL_DECLINED.name().equals(event.resultCode())
                        && "CANCEL_DECLINED".equals(event.declineCode())
                        && "cancel result reused by original".equals(event.note())
        ));
    }

    /**
     * [시나리오] UT-2-CANCEL-IDEMP-005
     * 같은 cancel posTrx를 다른 original에 재사용하면 원거래 조회 전에 CONFLICT를 반환한다.
     *
     * Given:
     * - 기존 PAYMENT_CANCEL row가 존재한다.
     * - 기존 row의 posTrx=2376-20260521-9991-3001이다.
     * - 기존 row의 originalPosTrx=2376-20260521-9991-1001, originalAttemptSeq=1이다.
     * - 기존 row의 cancelStatus=CANCELLED, cancelApprovalNo=C998855이다.
     *
     * When:
     * - 같은 cancel posTrx=2376-20260521-9991-3001로 취소 요청이 들어온다.
     * - 요청 originalPosTrx=2376-20260521-9991-1002, originalAttemptSeq=1로 기존 row와 다르다.
     *
     * Then:
     * - BusinessException이 발생한다.
     * - ResultCode=CONFLICT, message=POS_TRX_ALREADY_USED를 검증한다.
     * - 원거래 조회, original 기준 취소 조회, insert, VAN cancel, update는 발생하지 않는다.
     */
    @Test
    void cancel_sameCancelPosTrxDifferentOriginal_shouldThrowConflict_beforeOriginalLookup() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3001",
                "2376-20260521-9991-1002",
                1,
                "4242424242424242"
        );
        PaymentCancel existing = paymentCancel(
                "2376-20260521-9991-3001",
                "2376-20260521-9991-1001",
                1,
                CancelStatus.CANCELLED,
                "C998855",
                null
        );

        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.of(existing));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.cancel(request)
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
     * [시나리오] UT-2-LOG-EVENT-008
     */
    @Test
    void cancel_originalNotApproved_shouldLogCancelNotAllowed() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3003",
                "2376-20260521-9991-1003",
                1,
                "4242424242424242"
        );

        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.empty());
        when(repository.findOriginalAttempt(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalDeclinedAttempt(request.originalAttemptSeq())));

        CancelResponse response = service.cancel(request);

        assertEquals(CancelResultStatus.CANCEL_NOT_ALLOWED, response.cancelStatus());
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
     * [시나리오] UT-2-LOG-EVENT-005
     */
    @Test
    void cancel_newRequest_vanCancelled_shouldLogPendingVanAndFinalizedEvents() {
        CancelRequest request = cancelRequest(
                "2376-20260521-9991-3004",
                "2376-20260521-9991-1004",
                1,
                "4242424242424242"
        );
        PaymentAttempt originalAttempt = originalApprovedAttempt(request.originalAttemptSeq());
        PaymentCancel pendingCancel = paymentCancel(
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                CancelStatus.PENDING,
                null,
                null
        );
        PaymentCancel cancelledCancel = paymentCancel(
                request.posTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                CancelStatus.CANCELLED,
                "C777777777",
                null
        );
        VanCancelRequest vanRequest = vanCancelRequest(request);

        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.empty());
        when(repository.findOriginalAttempt(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalAttempt));
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                request.originalPosTrx(),
                request.originalAttemptSeq()
        )).thenReturn(Optional.empty());
        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenReturn(Optional.of(pendingCancel));
        when(vanCancelAssembler.getVanCancelRequest(request, originalAttempt))
                .thenReturn(vanRequest);
        when(vanGateway.cancel(vanRequest))
                .thenReturn(vanCancelResCancelled(request, "C777777777"));
        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(cancelledCancel));

        CancelResponse response = service.cancel(request);

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        verify(repository).insertPendingCancel(any(CancelInsertParam.class));
        verify(vanGateway).cancel(eq(vanRequest));
        verifyCancelNewRequestEvents();
    }

    /**
     * 현재 cancel posTrx는 미사용이고, 원승인은 APPROVED로 존재하는 기본 취소 재응답 조건을 만든다.
     */
    private void givenNoCurrentCancelAndApprovedOriginal(CancelRequest request) {
        when(repository.findByPosTrx(request.posTrx())).thenReturn(Optional.empty());
        when(repository.findOriginalAttempt(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalApprovedAttempt(request.originalAttemptSeq())));
    }

    /**
     * 같은 cancel posTrx 충돌 경로에서 원거래 조회와 신규 취소 흐름이 모두 차단됐는지 검증한다.
     */
    private void verifySameCancelPosTrxConflictBlocked(CancelRequest request) {
        verify(repository).findByPosTrx(request.posTrx());
        verify(repository, never()).findOriginalAttempt(any(), anyInt());
        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(any(), anyInt());
        verify(repository, never()).insertPendingCancel(any(CancelInsertParam.class));
        verify(vanGateway, never()).cancel(any(VanCancelRequest.class));
        verify(vanCancelAssembler, never()).getVanCancelRequest(any(), any());
        verify(repository, never()).updateCancelResult(any(CancelResultUpdateParam.class));
    }

    /**
     * original 기준 기존 취소 row 재응답 경로에서 VAN 호출과 DB insert/update가 없었는지 검증한다.
     */
    private void verifyExistingOriginalCancelRepliedWithoutVan(CancelRequest request) {
        verify(repository).findByPosTrx(request.posTrx());
        verify(repository).findOriginalAttempt(request.originalPosTrx(), request.originalAttemptSeq());
        verify(repository).findByOriginalPosTrxAndOriginalAttemptSeq(
                request.originalPosTrx(),
                request.originalAttemptSeq()
        );
        verify(repository, never()).insertPendingCancel(any(CancelInsertParam.class));
        verify(vanGateway, never()).cancel(any(VanCancelRequest.class));
        verify(vanCancelAssembler, never()).getVanCancelRequest(any(), any());
        verify(repository, never()).updateCancelResult(any(CancelResultUpdateParam.class));
    }

    /**
     * 테스트 시나리오별 cancel posTrx와 original 식별자를 받아 취소 요청 DTO를 만든다.
     */
    private CancelRequest cancelRequest(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String cardNo
    ) {
        return new CancelRequest(posTrx, originalPosTrx, originalAttemptSeq, cardNo);
    }

    /**
     * 취소 가능한 원승인 APPROVED attempt fixture를 만든다.
     */
    private PaymentAttempt originalApprovedAttempt(int attemptSeq) {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A151472400",
                null,
                "42424242",
                "4242",
                attemptSeq,
                20000,
                "VAN-TRX-ORIGINAL"
        );
    }

    private PaymentAttempt originalDeclinedAttempt(int attemptSeq) {
        return new PaymentAttempt(
                PaymentFinalStatus.DECLINED.name(),
                null,
                "05",
                "42424242",
                "4242",
                attemptSeq,
                20000,
                "VAN-TRX-ORIGINAL"
        );
    }

    /**
     * cancel posTrx, original 식별자, 취소 상태를 조합할 수 있는 PaymentCancel 공통 fixture 생성 함수다.
     */
    private PaymentCancel paymentCancel(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            CancelStatus cancelStatus,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new PaymentCancel(
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                cancelStatus,
                cancelApprovalNo,
                declineCode
        );
    }

    private VanCancelRequest vanCancelRequest(CancelRequest request) {
        return VanCancelRequest.builder()
                .posTrx(request.posTrx())
                .originalPosTrx(request.originalPosTrx())
                .originalAttemptSeq(request.originalAttemptSeq())
                .amount(20000)
                .approvalNo("A151472400")
                .vanTrxId("VAN-TRX-ORIGINAL")
                .cardLast4("4242")
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

    private void verifyCancelNewRequestEvents() {
        ArgumentCaptor<PaymentEventLogInsertParam> captor =
                ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);

        verify(paymentEventLogRecorder, times(4)).record(captor.capture());
        List<PaymentEventLogInsertParam> events = captor.getAllValues();

        // 신규 취소 성공 흐름은 pending 생성 -> VAN 요청 -> VAN 응답 -> DB 확정 순서가 중요하다.
        assertEquals(PaymentEventType.CANCEL_PENDING_CREATED, events.get(0).eventType());
        assertEquals(PaymentEventType.CANCEL_VAN_REQUESTED, events.get(1).eventType());
        assertEquals(PaymentEventType.CANCEL_VAN_RESULT_RECEIVED, events.get(2).eventType());
        assertEquals(PaymentEventType.CANCEL_FINALIZED, events.get(3).eventType());

        PaymentEventLogInsertParam pendingCreated = events.get(0);
        assertEquals(CancelStatus.PENDING.name(), pendingCreated.statusSnapshot());
        assertEquals("cancel pending created", pendingCreated.note());

        PaymentEventLogInsertParam vanRequested = events.get(1);
        assertEquals(CancelStatus.PENDING.name(), vanRequested.statusSnapshot());
        assertEquals("VAN cancel requested", vanRequested.note());

        PaymentEventLogInsertParam vanResultReceived = events.get(2);
        assertEquals(CancelStatus.CANCELLED.name(), vanResultReceived.statusSnapshot());
        assertEquals("VAN-TRX-CANCELLED", vanResultReceived.vanTrxId());
        assertEquals("C777777777", vanResultReceived.approvalNo());
        Assertions.assertNull(vanResultReceived.declineCode());
        assertEquals("VAN cancel result received", vanResultReceived.note());

        PaymentEventLogInsertParam finalized = events.get(3);
        assertEquals(ResultCode.OK.name(), finalized.resultCode());
        assertEquals(CancelStatus.CANCELLED.name(), finalized.statusSnapshot());
        assertEquals("C777777777", finalized.approvalNo());
        assertEquals("cancel finalized", finalized.note());

        events.forEach(event -> {
            Assertions.assertEquals("2376-20260521-9991-3004", event.posTrx());
            Assertions.assertNull(event.currentTrxNo());
            Assertions.assertEquals("2376-20260521-9991-1004", event.originalPosTrx());
            Assertions.assertEquals(1, event.originalAttemptSeq());
        });
    }
}
