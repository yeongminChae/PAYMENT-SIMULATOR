package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.validate.CancelRequestValidator;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.CancelValidationError;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PaymentCancelServiceImplTest {

    private static final CardFingerprintPolicy CARD_FINGERPRINT_POLICY =
            new CardFingerprintPolicy("card-fingerprint-test-secret-key");

    // ===== UT IDs (Cancel Flow) =====
    private static final String UT_C2_001 = "UT-PAYMENT-CANCEL-001"; // C2 invalid
    private static final String UT_C3_001 = "UT-PAYMENT-CANCEL-002"; // C3 original not found
    private static final String UT_C4_001 = "UT-PAYMENT-CANCEL-003"; // C4 original not approved
    private static final String UT_C4_CARD_001 = "UT-PAYMENT-CANCEL-CARD-001"; // C4 card mismatch
    private static final String UT_C4_002 = "UT-PAYMENT-CANCEL-004"; // C4 existing PENDING
    private static final String UT_C4_003 = "UT-PAYMENT-CANCEL-005"; // C4 existing CANCELLED
    private static final String UT_C4_004 = "UT-PAYMENT-CANCEL-006"; // C4 existing CANCEL_DECLINED
    private static final String UT_C8_001 = "UT-PAYMENT-CANCEL-007"; // 신규 -> VAN CANCELLED -> C7 update -> C8
    private static final String UT_C8_002 = "UT-PAYMENT-CANCEL-008"; // 신규 -> VAN CANCEL_DECLINED -> C7 update -> C8
    private static final String UT_C8_003 = "UT-PAYMENT-CANCEL-009"; // 신규 -> VAN PENDING -> update 없음 -> retryLater
    private static final String UT_C5_001 = "UT-PAYMENT-CANCEL-010"; // C5 insert miss -> reread existing cancel

    private PaymentCancelService service;
    private PaymentCancelRepository repository;
    private VanGateway vanGateway;
    private CancelRequestValidator validator;
    private VanCancelAssembler vanCancelAssembler;
    private PaymentEventLogRecorder paymentEventLogRecorder;

    private CancelRequest baseReq;

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
                paymentEventLogRecorder,
                CARD_FINGERPRINT_POLICY
        );

        baseReq = new CancelRequest(
                "2376-20260519-9991-2001",
                "2376-20260519-9991-1001",
                1,
                "4242424242424242"
        );
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-001
     * <p>
     * [시나리오]
     * - Given: validator.validate()가 INVALID 계열 예외를 던진다
     * - When : service.cancel() 호출
     * - Then : 예외가 그대로 전파된다
     * - And  : C2에서 종료되므로 repository / vanGateway / vanCancelAssembler 호출이 없어야 한다
     * <p>
     * [흐름도]
     * C1 -> C2(FAIL) 종료
     */
    @Test
    @DisplayName("취소 요청 검증에 실패하면 예외를 전파하고 저장소와 VAN을 호출하지 않는다")
    void cancel_C2_invalid_shouldThrow_andNoCalls() {
        doThrow(new BusinessException(
                ResultCode.INVALID,
                CancelValidationError.INVALID_REQUEST.code()
        ))
                .when(validator)
                .validate(baseReq);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.cancel(baseReq)
        );

        assertEquals(ResultCode.INVALID, exception.getResultCode());
        assertEquals(CancelValidationError.INVALID_REQUEST.code(), exception.getMessage());

        verify(validator).validate(baseReq);
        verifyNoInteractions(repository, vanGateway, vanCancelAssembler);

    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-002
     * <p>
     * [시나리오]
     * - Given: 원거래 조회 결과 Optional.empty()
     * - When : service.cancel() 호출
     * - Then : BusinessException(ResultCode.NOT_FOUND)
     * - And  : 원거래가 없으므로 기존 cancel row 조회/C5 insert/VAN cancel 호출이 없어야 한다
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3(NOT_FOUND) 종료
     */
    @Test
    @DisplayName("원승인 거래가 없으면 NOT_FOUND 예외를 던지고 취소를 진행하지 않는다")
    void cancel_C3_originalAttemptNotFound_shouldThrowNotFound_andNoVanCall() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty());

        // when + then
        BusinessException exception =
                assertThrows(BusinessException.class, () -> service.cancel(baseReq));
        assertEquals(ResultCode.NOT_FOUND, exception.getResultCode());

        // then
        verify(validator).validate(baseReq);
        verify(repository).findOriginalAttempt(originalPosTrx, originalAttemptSeq);
        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(
                originalPosTrx,
                originalAttemptSeq
        );
        verify(repository, never()).insertPendingCancel(any());
        verifyNoInteractions(vanGateway, vanCancelAssembler);
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-003
     * <p>
     * [시나리오]
     * - Given: 원거래는 존재하지만 finalStatus=DECLINED 또는 UNKNOWN_TIMEOUT 또는 PROCESSING
     * - When : service.cancel() 호출
     * - Then : CANCEL_NOT_ALLOWED 응답
     * - And  : 기존 cancel row 조회/C5 insert/VAN cancel 호출이 없어야 한다
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3(원거래 존재) -> C4-1(NOT_APPROVED) -> C8(CANCEL_NOT_ALLOWED)
     */
    @Test
    @DisplayName("원거래가 승인 상태가 아니면 취소 불가 응답을 반환하고 취소 row와 VAN 호출을 만들지 않는다")
    void cancel_C4_originalNotApproved_shouldReturnCancelNotAllowed_withoutCancelRowAndVanCall() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        PaymentAttempt originalAttempt = originalDeclinedAttempt();
        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalAttempt));

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.CANCEL_NOT_ALLOWED, res.cancelStatus());
        assertEquals("ORIGINAL_NOT_APPROVED", res.declineCode());

        verify(validator).validate(baseReq);
        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(
                originalPosTrx,
                originalAttemptSeq
        );
        verify(repository, never()).insertPendingCancel(any());
        verifyNoInteractions(vanGateway, vanCancelAssembler);
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-004
     * <p>
     * [시나리오]
     * - Given: 원거래 APPROVED
     * - And  : 기존 PAYMENT_CANCEL row 상태가 PENDING
     * - When : service.cancel() 호출
     * - Then : RETRY_LATER 성격의 응답
     * - And  : 이미 취소 처리중이므로 C5 insert/VAN cancel 호출이 없어야 한다
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3 -> C4-1(APPROVED) -> C4-2(CARD_MATCH) -> C4-3(PENDING) -> C8(RETRY_LATER)
     */
    @Test
    @DisplayName("기존 취소가 PENDING이면 재시도 응답을 반환하고 VAN을 다시 호출하지 않는다")
    void cancel_C4_existingPending_shouldReturnRetryLater_withoutInsertAndVanCall() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();
        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));

        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(pendingCancel()));

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.RETRY_LATER, res.cancelStatus());
        verify(repository, never()).insertPendingCancel(any());
        verifyNoInteractions(vanGateway, vanCancelAssembler);

    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-005
     * <p>
     * [시나리오]
     * - Given: 원거래 APPROVED
     * - And  : 기존 PAYMENT_CANCEL row 상태가 CANCELLED
     * - When : service.cancel() 호출
     * - Then : 기존 DB 취소 결과를 재응답한다
     * - And  : 기존 CANCELLED row는 신규 취소 성공이 아니라 ALREADY_CANCELLED로 응답한다
     * - And  : C5 insert/VAN cancel 호출이 없어야 한다
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3 -> C4-1(APPROVED) -> C4-2(CARD_MATCH) -> C4-3(CANCELLED) -> C8(DB 재응답)
     */
    @Test
    @DisplayName("기존 취소가 CANCELLED이면 이미 취소된 결과를 반환하고 VAN을 다시 호출하지 않는다")
    void cancel_C4_existingCancelled_shouldReturnDbCancelled_withoutInsertAndVanCall() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));

        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(cancelledCancel()));

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.ALREADY_CANCELLED, res.cancelStatus());
        assertEquals("A137515458", res.cancelApprovalNo());

        verify(repository, never()).insertPendingCancel(any());
        verifyNoInteractions(vanGateway, vanCancelAssembler);
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-006
     * <p>
     * [시나리오]
     * - Given: 원거래 APPROVED
     * - And  : 기존 PAYMENT_CANCEL row 상태가 CANCEL_DECLINED
     * - When : service.cancel() 호출
     * - Then : 기존 DB 거절 결과를 재응답한다
     * - And  : C5 insert/VAN cancel 호출이 없어야 한다
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3 -> C4-1(APPROVED) -> C4-2(CARD_MATCH) -> C4-3(CANCEL_DECLINED) -> C8(DB 재응답)
     */
    @Test
    @DisplayName("기존 취소가 CANCEL_DECLINED이면 저장된 거절 결과를 반환하고 VAN을 다시 호출하지 않는다")
    void cancel_C4_existingCancelDeclined_shouldReturnDbDeclined_withoutInsertAndVanCall() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));

        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(cancelDeclinedCancel()));

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.CANCEL_DECLINED, res.cancelStatus());
        assertEquals("05", res.declineCode());

        verify(repository, never()).insertPendingCancel(any());
        verifyNoInteractions(vanGateway, vanCancelAssembler);
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-007
     * <p>
     * [시나리오]
     * - Given: 원거래 APPROVED
     * - And  : 기존 cancel row 없음
     * - And  : C5 PENDING row insert 성공
     * - And  : VAN cancel 결과 CANCELLED
     * - And  : C7 updateCancelResult returning 성공
     * - When : service.cancel() 호출
     * - Then : DB update row 기준 CANCELLED 응답
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3 -> C4 -> C5(PENDING insert 성공)
     * -> C6(VAN CANCELLED) -> C7(update 성공) -> C8(CANCELLED)
     */
    @Test
    @DisplayName("신규 취소가 VAN에서 승인되면 CANCELLED 결과를 저장하고 반환한다")
    void cancel_newRequest_vanCancelled_updateSuccess_shouldReturnCancelled_C8() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));

        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty());

        when(repository.insertPendingCancel(any()))
                .thenReturn(Optional.of(pendingCancel()));

        when(vanCancelAssembler.getVanCancelRequest(baseReq, originalApprovedAttempt()))
                .thenReturn(vanCancelRequest());

        when(vanGateway.cancel(vanCancelRequest()))
                .thenReturn(vanCancelResCancelled());

        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(cancelledCancel()));

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.CANCELLED, res.cancelStatus());
        assertEquals(cancelledCancel().cancelApprovalNo(), res.cancelApprovalNo());

        verify(repository).insertPendingCancel(any());
        verify(vanCancelAssembler).getVanCancelRequest(baseReq, originalApprovedAttempt());
        verify(vanGateway).cancel(vanCancelRequest());
        verify(repository).updateCancelResult(any());
    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-008
     * <p>
     * [시나리오]
     * - Given: 원거래 APPROVED
     * - And  : 기존 cancel row 없음
     * - And  : C5 PENDING row insert 성공
     * - And  : VAN cancel 결과 CANCEL_DECLINED
     * - And  : C7 updateCancelResult returning 성공
     * - When : service.cancel() 호출
     * - Then : DB update row 기준 CANCEL_DECLINED 응답
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3 -> C4 -> C5(PENDING insert 성공)
     * -> C6(VAN CANCEL_DECLINED) -> C7(update 성공) -> C8(CANCEL_DECLINED)
     */
    @Test
    @DisplayName("신규 취소가 VAN에서 거절되면 CANCEL_DECLINED 결과를 저장하고 반환한다")
    void cancel_newRequest_vanDeclined_updateSuccess_shouldReturnDeclined_C8() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        PaymentAttempt originalAttempt = originalApprovedAttempt();
        PaymentCancel pendingCancel = pendingCancel();
        VanCancelRequest vanReq = vanCancelRequest();
        VanCancelResponse vanRes = vanCancelResDeclined();
        PaymentCancel updatedCancel = cancelDeclinedCancel();

        // 원거래 APPROVED
        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));

        // 기존 cancel row 없음
        when(repository
                .findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty());

        // C5 PENDING row insert 성공
        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenReturn(Optional.of(pendingCancel));

        // C6 VAN cancel request 조립
        when(vanCancelAssembler.getVanCancelRequest(baseReq, originalAttempt))
                .thenReturn(vanReq);

        // C6 VAN cancel 결과 CANCEL_DECLINED
        when(vanGateway.cancel(vanReq))
                .thenReturn(vanRes);

        // C7 updateCancelResult returning 성공
        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(updatedCancel));

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.CANCEL_DECLINED, res.cancelStatus());
        assertEquals(updatedCancel.declineCode(), res.declineCode());

        verify(repository).findOriginalAttempt(originalPosTrx, originalAttemptSeq);
        verify(repository).findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
        verify(repository).insertPendingCancel(any(CancelInsertParam.class));
        verify(vanCancelAssembler).getVanCancelRequest(baseReq, originalAttempt);
        verify(vanGateway).cancel(vanReq);
        verify(repository).updateCancelResult(any(CancelResultUpdateParam.class));

    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-009
     * <p>
     * [시나리오]
     * - Given: 원거래 APPROVED
     * - And  : 기존 cancel row 없음
     * - And  : C5 PENDING row insert 성공
     * - And  : VAN cancel 결과 PENDING
     * - When : service.cancel() 호출
     * - Then : RETRY_LATER 성격의 PENDING 응답
     * - And  : C5에서 이미 PENDING row가 생성되어 있으므로 C7 updateCancelResult는 호출하지 않는다
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3 -> C4 -> C5(PENDING insert 성공)
     * -> C6(VAN PENDING) -> C8(RETRY_LATER/PENDING 유지)
     */
    @Test
    @DisplayName("VAN 취소 결과가 PENDING이면 결과를 확정하지 않고 재시도 응답을 반환한다")
    void cancel_newRequest_vanPending_shouldReturnRetryLater_withoutUpdate_C8() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        // 원거래 APPROVED
        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));
        // 기존 cancel row 없음
        PaymentAttempt originalAttempt = originalApprovedAttempt();
        PaymentCancel pendingCancel = pendingCancel();
        VanCancelRequest vanReq = vanCancelRequest();
        VanCancelResponse vanRes = vanCancelResPending();

        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalAttempt));

        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty());

        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenReturn(Optional.of(pendingCancel));

        when(vanCancelAssembler.getVanCancelRequest(baseReq, originalAttempt))
                .thenReturn(vanReq);

        when(vanGateway.cancel(vanReq))
                .thenReturn(vanRes);

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.RETRY_LATER, res.cancelStatus());
        assertEquals(pendingCancel.declineCode(), res.declineCode());
        verify(repository, never()).updateCancelResult(any());

    }

    /**
     * [UT_ID] UT-PAYMENT-CANCEL-010
     * <p>
     * [시나리오]
     * - Given: 원거래 APPROVED
     * - And  : 기존 cancel row 없음으로 조회됨
     * - But  : C5 insertPendingCancel 결과 Optional.empty()
     * - And  : 재조회 결과 기존 cancel row가 존재한다
     * - When : service.cancel() 호출
     * - Then : 재조회된 cancel row 기준으로 상태별 응답한다
     * - And  : insert 실패 요청은 VAN cancel을 호출하지 않는다
     * <p>
     * [흐름도]
     * C1 -> C2 -> C3 -> C4 -> C5(insert miss)
     * -> C5-1(기존 cancel row 재조회) -> C8(DB 재응답)
     */
    @Test
    @DisplayName("PENDING row 생성에 실패하면 기존 취소를 재조회하고 VAN을 호출하지 않는다")
    void cancel_C5_insertMiss_thenRereadExistingCancel_shouldReturnDbResponse_withoutVanCall() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        PaymentAttempt originalAttempt = originalApprovedAttempt();
        PaymentCancel rereadCancel = cancelledCancel();

        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalAttempt));

        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(rereadCancel));

        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenReturn(Optional.empty());

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.ALREADY_CANCELLED, res.cancelStatus());
        assertEquals(rereadCancel.cancelApprovalNo(), res.cancelApprovalNo());

        verify(repository, times(2))
                .findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
        verify(repository).insertPendingCancel(any(CancelInsertParam.class));
        verify(vanCancelAssembler, never()).getVanCancelRequest(any(), any());
        verifyNoInteractions(vanGateway);
    }

    /**
     * [UT_ID] UT-2.1-FP-007 / UT-2.1-FP-008
     * Given
     * - 원승인 거래가 APPROVED 상태로 존재한다.
     * - 원승인 attempt에는 4242424242424242 카드의 fingerprint가 저장되어 있다.
     * - 취소 요청 cardNo는 4111111111111111 이다.
     * - 취소 요청 cardNo는 다른 fingerprint를 생성하므로 원승인 카드와 다르다.
     * <p>
     * When
     * - cancel 서비스를 호출한다.
     * <p>
     * Then
     * - BusinessException이 발생한다.
     * - resultCode는 CANCEL_NOT_ALLOWED 이다.
     * - message는 CARD_MISMATCH 이다.
     * - VAN cancel은 호출되지 않는다.
     * - PAYMENT_CANCEL PENDING insert도 호출되지 않는다.
     */
    @Test
    @DisplayName("취소 카드 fingerprint가 원승인과 다르면 취소 row 생성과 VAN 호출을 차단한다")
    void cancel_C4_cardFingerprintMismatch_shouldThrowCancelNotAllowed_withoutCancelRowAndVanCall() {
        // given
        String cancelCardNo = "4111111111111111";

        PaymentAttempt originalAttempt = originalApprovedAttempt();

        CancelRequest request = new CancelRequest(
                "2376-20260519-9991-2001-01",
                "2376-20260519-9991-1001-01",
                1,
                cancelCardNo
        );

        when(repository.findOriginalAttempt(
                request.originalPosTrx(),
                request.originalAttemptSeq())
        ).thenReturn(Optional.of(originalAttempt));

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.cancel(request)
        );

        // then
        assertThat(exception.getResultCode()).isEqualTo(ResultCode.CANCEL_NOT_ALLOWED);
        assertThat(exception.getMessage()).isEqualTo("CARD_MISMATCH");

        verify(repository).findOriginalAttempt(request.originalPosTrx(), request.originalAttemptSeq());
        verify(repository, never()).findByOriginalPosTrxAndOriginalAttemptSeq(anyString(), anyInt());
        verify(vanGateway, never()).cancel(any());
        verifyNoInteractions(vanCancelAssembler);
        verify(repository, never()).insertPendingCancel(any());
    }

    /**
     * [UT_ID] UT-2.1-FP-006
     * Given
     * - 원승인 카드와 취소 요청 카드는 BIN8과 last4가 같다.
     * - 두 카드의 PAN이 달라 서로 다른 fingerprint가 생성된다.
     * <p>
     * Then
     * - 기존 BIN8/last4 기준으로는 통과할 수 있어도 fingerprint 기준으로 CARD_MISMATCH 처리한다.
     */
    @Test
    @DisplayName("BIN8과 last4가 같아도 fingerprint가 다르면 CARD_MISMATCH로 취소를 거절한다")
    void cancel_sameBinAndLast4ButDifferentFingerprint_shouldThrowCardMismatch() {
        // 42424242 | 1111 | 4242: 원승인 PAN과 BIN8/last4는 같고 중간 숫자만 다르다.
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

        when(repository.findOriginalAttempt(request.originalPosTrx(), request.originalAttemptSeq()))
                .thenReturn(Optional.of(originalAttempt));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.cancel(request)
        );

        assertThat(exception.getResultCode()).isEqualTo(ResultCode.CANCEL_NOT_ALLOWED);
        assertThat(exception.getMessage()).isEqualTo("CARD_MISMATCH");
    }

    /**
     * [UT_ID] UT-2.1-FP-005
     * Given
     * - 원승인 거래가 APPROVED 상태다.
     * - 원승인 attempt에는 4242424242424242 카드의 fingerprint가 저장되어 있다.
     * - 취소 요청 cardNo = 4242424242424242
     * <p>
     * When
     * - cancel 서비스를 호출한다.
     * <p>
     * Then
     * - BusinessException이 발생하지 않는다.
     * - 기존처럼 PAYMENT_CANCEL PENDING row 생성 흐름으로 간다.
     * - VAN cancel이 호출된다.
     * - 최종 cancel 응답이 기존 기대값과 같다.
     */
    @Test
    @DisplayName("원승인 카드와 취소 요청 카드가 같으면 기존 취소 흐름을 진행한다")
    void cancel_sameCard_shouldProceedCancelFlow() {
        // given
        String originalPosTrx = baseReq.originalPosTrx();
        int originalAttemptSeq = baseReq.originalAttemptSeq();

        when(repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.of(originalApprovedAttempt()));

        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq))
                .thenReturn(Optional.empty());

        when(repository.insertPendingCancel(any()))
                .thenReturn(Optional.of(pendingCancel()));

        when(vanCancelAssembler.getVanCancelRequest(baseReq, originalApprovedAttempt()))
                .thenReturn(vanCancelRequest());

        when(vanGateway.cancel(vanCancelRequest()))
                .thenReturn(vanCancelResCancelled());

        when(repository.updateCancelResult(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.of(cancelledCancel()));

        // when
        CancelResponse res = service.cancel(baseReq);

        // then
        assertEquals(CancelResultStatus.CANCELLED, res.cancelStatus());
        assertEquals(cancelledCancel().cancelApprovalNo(), res.cancelApprovalNo());

        verify(repository).insertPendingCancel(any());
        verify(vanCancelAssembler).getVanCancelRequest(baseReq, originalApprovedAttempt());
        verify(vanGateway).cancel(vanCancelRequest());
        verify(repository).updateCancelResult(any());
    }

    private PaymentAttempt originalApprovedAttempt() {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A207076083",
                null,
                "42424242",
                "4242",
                CARD_FINGERPRINT_POLICY.generate("4242424242424242"),
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

    private VanCancelRequest vanCancelRequest() {
        return VanCancelRequest.builder()
                .posTrx(baseReq.posTrx())
                .originalPosTrx(baseReq.originalPosTrx())
                .originalAttemptSeq(baseReq.originalAttemptSeq())
                .amount(10000)
                .approvalNo("A207076083")
                .vanTrxId("2376-20260519-9991-1001-01")
                .cardLast4("4242")
                .build();
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
