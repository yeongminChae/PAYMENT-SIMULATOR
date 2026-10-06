package com.chaeyeongmin.payment_sim.payment.application.cancel.transaction;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelRequest;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.application.cancel.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.payment.application.cancel.support.CancelReservationHandler;
import com.chaeyeongmin.payment_sim.payment.application.cancel.transaction.model.CancelPrepareResult;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelCardVerificationPolicy;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.PaymentCancel;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 취소 TX1 준비 구간만 담당한다.
 *
 * <p>
 * 원승인 posTrx lock, 원승인/카드 검증, 기존 취소 재응답 판단, 신규 PENDING row 선점을 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelPrepareTxService {

    private final PaymentCancelRepository cancelRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final CancelCardVerificationPolicy policy;
    private final CancelReservationHandler reservationHandler;
    private final CancelEventRecorder recorder;

    /**
     * VAN 취소를 호출하기 전 DB 기준으로 취소 가능 여부를 결정하고 신규 취소 row를 선점한다.
     *
     * <p>
     * 이 메서드가 completed 결과를 반환하면 이미 DB 상태만으로 응답이 확정된 경로이므로
     * 호출자는 VAN 취소를 호출하면 안 된다.
     */
    @Transactional
    public CancelPrepareResult prepare(CancelRequest request) {
        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        // C2-1: cancel posTrx 빠른 사전검사.
        // - 이미 처리된 취소 거래번호는 lock을 기다리지 않고 곧바로 CONFLICT로 거른다.
        // - 단, 이 결과만으로 최종 판단하지 않는다. lock 대기 중 다른 요청이 같은 posTrx를 만들 수 있으므로
        //   originalPosTrx lock 획득 뒤 한 번 더 확인한다.
        assertPosTrxAvailable(posTrx, originalPosTrx, originalAttemptSeq);

        acquireOriginalPosTrxLock(originalPosTrx);

        // C4-1: cancel posTrx 사용 여부 확인.
        // - MVP2에서는 cancel posTrx를 1회용 취소 거래번호로 본다.
        // - 이미 사용된 cancel posTrx가 다시 들어오면 같은 original 여부와 관계없이 거래번호 중복으로 차단한다.
        // - 카드가 원승인과 다르더라도 POS_TRX_ALREADY_USED가 CARD_MISMATCH보다 우선한다.
        // - 이 검사는 원거래 조회보다 먼저 수행한다.
        //   같은 cancel posTrx를 다른 original에 붙여 재사용하는 요청도 원거래 존재 여부와 무관하게 실패해야 하기 때문이다.
        // - lock 이후 재검사이므로, 기다리는 동안 앞선 요청이 만든 cancel row까지 반영해 최종 판단한다.
        assertPosTrxAvailable(posTrx, originalPosTrx, originalAttemptSeq);

        PaymentAttempt originalAttempt =
                getOriginalAttemptOrThrow(posTrx, originalPosTrx, originalAttemptSeq);

        Optional<CancelPrepareResult> notAllowedResult =
                resolveNotAllowed(posTrx, originalPosTrx, originalAttemptSeq, originalAttempt);
        if (notAllowedResult.isPresent()) return notAllowedResult.get();

        assertCardMatches(request, posTrx, originalPosTrx, originalAttemptSeq, originalAttempt);

        Optional<CancelPrepareResult> existingCancelResult =
                reservationHandler.reuseExistingByOriginal(
                        request,
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq);

        if (existingCancelResult.isPresent()) return existingCancelResult.get();

        return reservationHandler.reserveOrRecover(
                request,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt
        );
    }

    /**
     * VAN에 cancel 요청이 전송되지 않은 경우 방금 만든 PENDING row를 정리한다.
     *
     * <p>Socket connect 실패처럼 request bytes가 나가지 않은 경우에만 사용해야 한다.
     */
    @Transactional
    public CancelResponse cleanupRequestNotSent(CancelPrepareResult prepared) {
        if (prepared == null || prepared.isCompleted()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "CANCEL_CLEANUP_INVALID_PREPARE_RESULT");
        }

        cancelRepository.deletePendingCancel(
                prepared.posTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq()
        );

        return CancelResponse.retryLater(
                prepared.posTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq()
        );
    }

    /**
     * 같은 원승인에 대한 취소 판단을 직렬화하기 위해 원승인 거래번호 기준 lock을 잡는다.
     */
    private void acquireOriginalPosTrxLock(String originalPosTrx) {
        // C3-0: 원승인 posTrx 기준 직렬화 lock 획득.
        // - 취소 요청의 posTrx는 C01~C20처럼 모두 다를 수 있으므로 lock key가 될 수 없다.
        // - 같은 원승인에 대한 취소 판단은 이 lock 이후의 DB 재조회 결과만 신뢰한다.
        // - 정상 승인 API로 생성된 원거래라면 PAYMENT_ATTEMPT_SEQ row가 반드시 존재해야 한다.
        //   row가 없으면 legacy/수동 데이터 불일치로 보고 조용히 취소를 진행하지 않는다.
        Optional<Integer> lockResult = attemptRepository.acquireExistingPosTrxLock(originalPosTrx);
        if (lockResult.isEmpty()) {
            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    "ORIGINAL_POS_TRX_LOCK_ROW_NOT_FOUND"
            );
        }
    }

    /**
     * 취소 대상 원승인 attempt를 조회하고, 존재하지 않으면 취소 row 생성 없이 NOT_FOUND로 중단한다.
     */
    private PaymentAttempt getOriginalAttemptOrThrow(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        // C3: 취소 대상 원거래 attempt 조회.
        // - 취소는 독립 거래처럼 보이지만 실제로는 원승인 attempt에 종속된다.
        // - 원거래가 없으면 취소 가능 여부도 판단할 수 없으므로 NOT_FOUND로 종료한다.
        Optional<PaymentAttempt> originalAttemptOpt =
                attemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq);

        // C3-1: 원거래 없음.
        // - DB에 원승인 attempt가 없으므로 PAYMENT_CANCEL row를 만들지 않는다.
        // - 존재하지 않는 거래를 VAN에 취소 요청하지 않는다.
        if (originalAttemptOpt.isEmpty()) {
            log.info("[cancel][C3] original attempt not found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );

            throw new BusinessException(
                    ResultCode.NOT_FOUND,
                    "취소 대상 원거래가 존재하지 않습니다."
            );
        }

        // C3-2: 원거래 존재.
        // - 이제 원거래의 최종 승인 상태를 보고 취소 가능 여부를 판단한다.
        PaymentAttempt originalAttempt = originalAttemptOpt.get();
        log.info("[cancel][C3] original attempt found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, originalFinalStatus={}",
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt.finalStatus()
        );

        return originalAttempt;
    }

    /**
     * 원승인 attempt가 취소 가능한 APPROVED 상태인지 검사하고, 불가하면 응답을 즉시 확정한다.
     */
    private Optional<CancelPrepareResult> resolveNotAllowed(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        // C4-1: 원거래 상태 검증.
        // - 취소 가능한 원거래는 APPROVED뿐이다.
        // - DECLINED/UNKNOWN_TIMEOUT/PROCESSING은 실제 승인 확정이 아니므로 취소 대상이 아니다.
        PaymentFinalStatus originalStatus = originalAttempt.getFinalStatusEnum();

        if (originalStatus != PaymentFinalStatus.APPROVED) {
            log.info("[cancel][C4] cancel not allowed. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, originalStatus={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    originalStatus
            );

            // C4-1 종료 응답.
            // - 이 결과는 DB의 cancel 상태가 아니라 "응답 전용 취소 불가 상태"다.
            // - 취소 row를 만들지 않으므로 후속 중복 취소 방지 대상도 아니다.
            recorder.recordCancelEvent(
                    PaymentEventType.CANCEL_NOT_ALLOWED,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    ResultCode.CANCEL_NOT_ALLOWED.name(),
                    originalStatus.name(),
                    null,
                    null,
                    "ORIGINAL_NOT_APPROVED",
                    "original attempt is not approved"
            );

            return Optional.of(
                    CancelPrepareResult.completed(
                            CancelResponse.cancelNotAllowed(
                                    posTrx,
                                    originalPosTrx,
                                    originalAttemptSeq,
                                    "ORIGINAL_NOT_APPROVED"
                            )
                    )
            );
        }

        return Optional.empty();
    }

    /**
     * 취소 요청의 카드가 원승인 카드와 같은지 검증한다.
     *
     * <p>
     * 카드가 다르면 취소 권한이 없는 요청으로 보고 VAN 호출과 cancel row 생성을 모두 막는다.
     */
    private void assertCardMatches(
            CancelRequest request,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        // C4-2: 취소 요청 카드와 원승인 카드의 동일성 검증.
        // - 요청 형식 검증(C2)을 통과한 PAN으로 fingerprint를 다시 생성한다.
        // - 원승인 attempt에 저장된 fingerprint와 일치할 때만 취소를 허용한다.
        // - 카드가 다르면 취소 권한이 없는 요청이므로 PAYMENT_CANCEL row를 만들거나 VAN을 호출하지 않는다.
        boolean cardMatches = policy.matchesOriginalAttempt(originalAttempt, request.cardNo());

        if (cardMatches == false) {
            log.warn(
                    "[cancel][C4-2] card mismatch. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, reason=CARD_MISMATCH",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );

            recorder.recordAfterRollback(
                    PaymentEventType.CANCEL_NOT_ALLOWED,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    ResultCode.CANCEL_NOT_ALLOWED.name(),
                    originalAttempt.getFinalStatusEnum().name(),
                    null,
                    null,
                    "CARD_MISMATCH",
                    "cancel request card does not match original attempt"
            );

            throw new BusinessException(
                    ResultCode.CANCEL_NOT_ALLOWED,
                    "CARD_MISMATCH"
            );
        }
    }


    /**
     * 취소 거래번호(posTrx)가 이미 사용됐는지 검사한다.
     *
     * <p>
     * 같은 원거래 재요청이라도 cancel posTrx 자체는 1회용 거래번호이므로 재사용을 허용하지 않는다.
     */
    private void assertPosTrxAvailable(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> existingCancelByPosTrxOpt = cancelRepository.findByPosTrx(posTrx);
        if (existingCancelByPosTrxOpt.isEmpty()) return;

        PaymentCancel existingCancelByPosTrx = existingCancelByPosTrxOpt.get();

        log.warn("[cancel][C4-1-conflict] cancel posTrx already used. posTrx={}, existingOriginalPosTrx={}, existingOriginalAttemptSeq={}, existingCancelStatus={}",
                posTrx,
                existingCancelByPosTrx.originalPosTrx(),
                existingCancelByPosTrx.originalAttemptSeq(),
                existingCancelByPosTrx.cancelStatus()
        );

        // cancel posTrx 자체가 이미 사용된 거래번호이므로, 같은 original 재요청도 허용하지 않는다.
        recorder.recordCancelEvent(
                PaymentEventType.CANCEL_CONFLICT,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                ResultCode.CONFLICT.name(),
                existingCancelByPosTrx.cancelStatus().name(),
                null,
                existingCancelByPosTrx.cancelApprovalNo(),
                existingCancelByPosTrx.declineCode(),
                "POS_TRX_ALREADY_USED"
        );

        throw new BusinessException(ResultCode.CONFLICT, "POS_TRX_ALREADY_USED");

    }

}
