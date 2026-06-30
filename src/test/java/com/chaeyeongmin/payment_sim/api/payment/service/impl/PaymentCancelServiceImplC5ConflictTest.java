package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelResponseFactory;
import com.chaeyeongmin.payment_sim.api.payment.validate.CancelRequestValidator;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.cancel.CancelCardVerificationPolicy;
import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * C5 PENDING insert unique 충돌 복구 테스트.
 *
 * <p>
 * 이 테스트 클래스가 검증하는 흐름:
 * - C4에서 기존 PAYMENT_CANCEL row가 없다고 판단한다.
 * - C5 PENDING insert 시 unique 충돌 예외가 발생한다.
 * - 서비스는 예외를 서버 오류로 전파하지 않고 original 기준으로 기존 row를 재조회한다.
 * - 재조회된 DB 상태를 API 응답 상태로 변환한다.
 * - C5 충돌 경로에서는 VAN cancel을 호출하지 않는다.
 */
class PaymentCancelServiceImplC5ConflictTest {

    private static final CardFingerprintPolicy CARD_FINGERPRINT_POLICY =
            new CardFingerprintPolicy("card-fingerprint-test-secret-key");
    private static final CancelCardVerificationPolicy CANCEL_CARD_VERIFICATION_POLICY =
            new CancelCardVerificationPolicy(CARD_FINGERPRINT_POLICY);

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
                CANCEL_CARD_VERIFICATION_POLICY,
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
     * [시나리오]
     * - Given: 원거래는 APPROVED이고, C4 최초 기존 cancel row 조회는 empty다.
     * - And  : C5 PENDING insert에서 unique 충돌 예외가 발생한다.
     * - And  : 충돌 후 original 기준 재조회 결과가 PENDING row다.
     * - When : cancel()을 호출한다.
     * - Then : API 응답은 RETRY_LATER다.
     * - And  : VAN cancel request 조립과 VAN cancel 호출은 없어야 한다.
     */
    @Test
    void cancel_C5_insertConflict_existingPending_shouldReturnRetryLater_withoutVanCall() {
        // C4: 원거래는 APPROVED라 취소 가능 상태다.
        givenApprovedOriginal();

        // C4 -> C5:
        // - 첫 번째 조회는 "기존 cancel row 없음"으로 응답해 C5 insert까지 내려가게 만든다.
        // - C5 충돌 후 두 번째 original 기준 재조회에서는 PENDING row를 반환한다.
        givenNoExistingCancelThenReread(Optional.of(pendingCancel()));

        // C5: PENDING insert 시점에 unique 충돌 예외가 발생한다.
        givenInsertPendingConflict();

        // When: 서비스는 예외를 전파하지 않고 기존 row 재조회 복구를 수행해야 한다.
        CancelResponse res = service.cancel(baseReq);

        // Then: 기존 row가 PENDING이면 API 응답은 RETRY_LATER다.
        assertEquals(CancelResultStatus.RETRY_LATER, res.cancelStatus());
        verifyC5ConflictRecoveryTried();
        assertNoVanCancelCall();
    }

    /**
     * [시나리오]
     * - Given: 원거래는 APPROVED이고, C4 최초 기존 cancel row 조회는 empty다.
     * - And  : C5 PENDING insert에서 unique 충돌 예외가 발생한다.
     * - And  : 충돌 후 original 기준 재조회 결과가 CANCELLED row다.
     * - When : cancel()을 호출한다.
     * - Then : API 응답은 ALREADY_CANCELLED다.
     * - And  : 기존 cancelApprovalNo를 재응답한다.
     * - And  : VAN cancel request 조립과 VAN cancel 호출은 없어야 한다.
     */
    @Test
    void cancel_C5_insertConflict_existingCancelled_shouldReturnAlreadyCancelled_withoutVanCall() {
        PaymentCancel existingCancel = cancelledCancel();

        // C4: 원거래는 APPROVED라 취소 가능 상태다.
        givenApprovedOriginal();

        // C4 -> C5:
        // - 첫 번째 조회는 empty로 응답해 신규 취소 insert 흐름으로 진입시킨다.
        // - C5 충돌 후 재조회에서는 이미 취소 완료된 CANCELLED row를 반환한다.
        givenNoExistingCancelThenReread(Optional.of(existingCancel));

        // C5: insert unique 충돌을 예외로 발생시킨다.
        givenInsertPendingConflict();

        // When: C5 충돌 요청은 VAN 호출 권한을 얻지 못한 중복 요청으로 처리되어야 한다.
        CancelResponse res = service.cancel(baseReq);

        // Then:
        // - DB row는 CANCELLED지만, 이번 요청은 신규 취소 성공이 아니므로 API는 ALREADY_CANCELLED다.
        // - 기존 cancelApprovalNo를 그대로 재응답한다.
        assertEquals(CancelResultStatus.ALREADY_CANCELLED, res.cancelStatus());
        assertEquals(existingCancel.cancelApprovalNo(), res.cancelApprovalNo());
        verifyC5ConflictRecoveryTried();
        assertNoVanCancelCall();
    }

    /**
     * [시나리오]
     * - Given: 원거래는 APPROVED이고, C4 최초 기존 cancel row 조회는 empty다.
     * - And  : C5 PENDING insert에서 unique 충돌 예외가 발생한다.
     * - And  : 충돌 후 original 기준 재조회 결과가 CANCEL_DECLINED row다.
     * - When : cancel()을 호출한다.
     * - Then : API 응답은 CANCEL_DECLINED다.
     * - And  : 기존 declineCode를 재응답한다.
     * - And  : VAN cancel request 조립과 VAN cancel 호출은 없어야 한다.
     */
    @Test
    void cancel_C5_insertConflict_existingDeclined_shouldReturnDeclined_withoutVanCall() {
        PaymentCancel existingCancel = cancelDeclinedCancel();

        // C4: 원거래는 APPROVED라 취소 가능 상태다.
        givenApprovedOriginal();

        // C4 -> C5:
        // - 첫 번째 조회는 empty로 응답해 C5 insert를 시도하게 한다.
        // - C5 충돌 후 재조회에서는 이미 취소 거절로 확정된 row를 반환한다.
        givenNoExistingCancelThenReread(Optional.of(existingCancel));

        // C5: insert unique 충돌을 예외로 발생시킨다.
        givenInsertPendingConflict();

        // When: 서비스는 기존 CANCEL_DECLINED row 기준으로 재응답해야 한다.
        CancelResponse res = service.cancel(baseReq);

        // Then: 기존 취소 거절 상태와 declineCode가 API 응답으로 보존된다.
        assertEquals(CancelResultStatus.CANCEL_DECLINED, res.cancelStatus());
        assertEquals(existingCancel.declineCode(), res.declineCode());
        verifyC5ConflictRecoveryTried();
        assertNoVanCancelCall();
    }

    /**
     * [시나리오]
     * - Given: 원거래는 APPROVED이고, C4 최초 기존 cancel row 조회는 empty다.
     * - And  : C5 PENDING insert에서 unique 충돌 예외가 발생한다.
     * - And  : 충돌 후 original 기준 재조회 결과도 empty다.
     * - When : cancel()을 호출한다.
     * - Then : API 응답은 방어적으로 RETRY_LATER다.
     * - And  : VAN cancel request 조립과 VAN cancel 호출은 없어야 한다.
     */
    @Test
    void cancel_C5_insertConflict_rereadEmpty_shouldReturnRetryLater_withoutVanCall() {
        // C4: 원거래는 APPROVED라 취소 가능 상태다.
        givenApprovedOriginal();

        // C4 -> C5:
        // - 첫 번째 조회는 empty로 응답해 C5 insert를 시도하게 한다.
        // - C5 충돌 후 재조회도 empty로 응답해 비정상 경합/정합성 이상 상황을 만든다.
        givenNoExistingCancelThenReread(Optional.empty());

        // C5: insert unique 충돌을 예외로 발생시킨다.
        givenInsertPendingConflict();

        // When: 충돌은 났지만 original 기준 row를 찾지 못한다.
        CancelResponse res = service.cancel(baseReq);

        // Then: row를 찾지 못한 이상 상황에서는 방어적으로 RETRY_LATER를 응답한다.
        assertEquals(CancelResultStatus.RETRY_LATER, res.cancelStatus());
        verifyC5ConflictRecoveryTried();
        assertNoVanCancelCall();
    }

    private void givenApprovedOriginal() {
        when(repository.findOriginalAttempt(baseReq.originalPosTrx(), baseReq.originalAttemptSeq()))
                .thenReturn(Optional.of(originalApprovedAttempt()));
    }

    private void givenNoExistingCancelThenReread(Optional<PaymentCancel> rereadCancel) {
        // 같은 repository 메서드가 두 번 호출된다.
        // 1회차: C4 기존 cancel row 확인. empty여야 C5 insert로 내려간다.
        // 2회차: C5 insert conflict 후 original 기준 복구 재조회.
        when(repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq()
        ))
                .thenReturn(Optional.empty())
                .thenReturn(rereadCancel);
    }

    private void givenInsertPendingConflict() {
        // 실제 DB unique 충돌은 Optional.empty가 아니라 DataIntegrityViolationException으로 올라올 수 있다.
        when(repository.insertPendingCancel(any(CancelInsertParam.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
    }

    private void verifyC5ConflictRecoveryTried() {
        // 같은 original 기준 조회가 총 2번 호출되어야 한다.
        // 1회차: C4 기존 cancel row 확인
        // 2회차: C5 insert conflict 후 복구 재조회
        verify(repository, times(2)).findByOriginalPosTrxAndOriginalAttemptSeq(
                baseReq.originalPosTrx(),
                baseReq.originalAttemptSeq()
        );

        // C5 PENDING insert 시도 자체는 한 번 발생해야 한다.
        verify(repository).insertPendingCancel(any(CancelInsertParam.class));
    }

    private void assertNoVanCancelCall() {
        // C5 insert conflict 경로는 VAN 호출 권한을 얻지 못한 중복/경합 요청이다.
        // 따라서 VAN request 조립도, VAN cancel 호출도 없어야 한다.
        verify(vanCancelAssembler, never()).getVanCancelRequest(any(), any());
        verify(vanGateway, never()).cancel(any());
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
}
