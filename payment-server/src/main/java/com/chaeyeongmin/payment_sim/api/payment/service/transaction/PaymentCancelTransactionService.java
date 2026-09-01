package com.chaeyeongmin.payment_sim.api.payment.service.transaction;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelResponseFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.support.PaymentResultCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.support.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentCancelPrepareResult;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.domain.policy.cancel.CancelCardVerificationPolicy;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelTransactionService {

    private final PaymentCancelRepository cancelRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final CancelCardVerificationPolicy policy;
    private final CancelResponseFactory factory;
    private final CancelEventRecorder recorder;

    @Transactional
    public PaymentCancelPrepareResult prepare(CancelRequest request) {
        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        // C2-1: cancel posTrx 빠른 사전검사.
        // - 이미 처리된 취소 거래번호는 lock을 기다리지 않고 곧바로 CONFLICT로 거른다.
        // - 단, 이 결과만으로 최종 판단하지 않는다. lock 대기 중 다른 요청이 같은 posTrx를 만들 수 있으므로
        //   originalPosTrx lock 획득 뒤 한 번 더 확인한다.
        assertCancelPosTrxNotUsed(posTrx, originalPosTrx, originalAttemptSeq);

        acquireOriginalPosTrxLock(originalPosTrx);

        // C4-1: cancel posTrx 사용 여부 확인.
        // - MVP2에서는 cancel posTrx를 1회용 취소 거래번호로 본다.
        // - 이미 사용된 cancel posTrx가 다시 들어오면 같은 original 여부와 관계없이 거래번호 중복으로 차단한다.
        // - 카드가 원승인과 다르더라도 POS_TRX_ALREADY_USED가 CARD_MISMATCH보다 우선한다.
        // - 이 검사는 원거래 조회보다 먼저 수행한다.
        //   같은 cancel posTrx를 다른 original에 붙여 재사용하는 요청도 원거래 존재 여부와 무관하게 실패해야 하기 때문이다.
        // - lock 이후 재검사이므로, 기다리는 동안 앞선 요청이 만든 cancel row까지 반영해 최종 판단한다.
        assertCancelPosTrxNotUsed(posTrx, originalPosTrx, originalAttemptSeq);

        PaymentAttempt originalAttempt =
                findOriginalAttemptOrThrow(posTrx, originalPosTrx, originalAttemptSeq);

        Optional<PaymentCancelPrepareResult> notAllowedResult =
                completeIfOriginalNotApproved(posTrx, originalPosTrx, originalAttemptSeq, originalAttempt);
        if (notAllowedResult.isPresent()) return notAllowedResult.get();

        assertCardMatches(request, posTrx, originalPosTrx, originalAttemptSeq, originalAttempt);

        Optional<PaymentCancelPrepareResult> existingCancelResult =
                completeIfExistingCancelByOriginal(request, posTrx, originalPosTrx, originalAttemptSeq);
        if (existingCancelResult.isPresent()) return existingCancelResult.get();

        return insertPendingCancelOrRecover(
                request,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt
        );
    }

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

    private PaymentAttempt findOriginalAttemptOrThrow(
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

    private Optional<PaymentCancelPrepareResult> completeIfOriginalNotApproved(
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
                    PaymentCancelPrepareResult.completed(
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

    private Optional<PaymentCancelPrepareResult> completeIfExistingCancelByOriginal(
            CancelRequest request,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        // C4-3: 기존 취소 row 확인.
        // - 원거래가 APPROVED여도 이미 취소 요청이 있었으면 VAN을 다시 호출하면 안 된다.
        // - 원거래 기준 unique 제약과 함께 "원승인 1건당 취소 1건" 정책을 보장한다.
        Optional<PaymentCancel> existingCancelByOriginalOpt =
                findCancelByOriginal(
                        "C4-existing-cancel-check",
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );

        if (existingCancelByOriginalOpt.isPresent()) {
            PaymentCancel existingCancelByOriginal = existingCancelByOriginalOpt.get();

            log.info("[cancel][C4] existing cancel row found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    existingCancelByOriginal.cancelStatus()
            );

            // C4-3-1: 기존 cancel row 재응답.
            // - 기존 row가 있으면 현재 요청의 posTrx가 달라도 원거래 기준 기존 취소 상태를 우선한다.
            // - 이 분기에서는 외부 VAN 취소를 절대 다시 호출하지 않는다.
            CancelResponse response = factory.fromExistingCancel(request, existingCancelByOriginal);
            recorder.recordCancelEvent(
                    PaymentEventType.CANCEL_REUSED_BY_ORIGINAL,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    PaymentResultCodeMapper.codeName(response.cancelStatus()),
                    response.cancelStatus().name(),
                    null,
                    existingCancelByOriginal.cancelApprovalNo(),
                    existingCancelByOriginal.declineCode(),
                    "cancel result reused by original"
            );

            return Optional.of(PaymentCancelPrepareResult.completed(response));
        }

        return Optional.empty();
    }

    private PaymentCancelPrepareResult insertPendingCancelOrRecover(
            CancelRequest request,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        // C4-3-2: 기존 cancel row가 없는 경우.
        // - 원거래는 APPROVED이고, 기존 취소 row도 없으므로 신규 취소 진행 가능 상태다.
        // - 여기까지 통과하면 C5에서 먼저 PENDING row를 만든다.
        // - PENDING 선저장은 외부 VAN 호출 전에 "취소 시도 중"이라는 내부 락/흔적을 남기는 역할이다.
        CancelInsertParam insertParam = CancelInsertParam.pending(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

        // C5: PENDING cancel row 생성.
        // - insertParam은 PAYMENT_CANCEL insert 전용 명령 객체다.
        // - CURRENT_TRX_NO에는 이번 취소 거래번호를, ORIGINAL_*에는 취소 대상 원거래 식별자를 담는다.
        // - insert가 성공한 요청만 VAN cancel 호출 권한을 얻는다.
        // - unique 충돌은 동일 원거래 취소가 먼저 접수된 경합으로 보고 original 기준 재조회 복구로 넘긴다.
        Optional<PaymentCancel> pendingCancelOpt;
        try {
            pendingCancelOpt = cancelRepository.insertPendingCancel(insertParam);

        } catch (DataIntegrityViolationException e) {
            // SQLite/MyBatis 조합에서는 unique 충돌이 Optional.empty가 아니라
            // DataIntegrityViolationException 계열 예외로 올라올 수 있다.
            // 이 경로에서는 VAN cancel을 호출하지 않고, 이미 생성된 PAYMENT_CANCEL row를 재조회해 재응답한다.
            log.warn("[cancel][C5-conflict] pending cancel insert conflict. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    e
            );

            return PaymentCancelPrepareResult.completed(
                    handleInsertPendingMiss(
                            request,
                            posTrx,
                            originalPosTrx,
                            originalAttemptSeq
                    )
            );
        }

        if (pendingCancelOpt.isPresent()) {
            PaymentCancel pendingCancel = pendingCancelOpt.get();

            log.info("[cancel][C5] pending cancel row created. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    pendingCancel.cancelStatus()
            );

            recorder.recordCancelEvent(
                    PaymentEventType.CANCEL_PENDING_CREATED,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    null,
                    CancelStatus.PENDING.name(),
                    null,
                    null,
                    null,
                    "cancel pending created"
            );

            return PaymentCancelPrepareResult.created(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                        originalAttempt
            );

        }

        return PaymentCancelPrepareResult.completed(
                handleInsertPendingMiss(
                        request,
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                )
        );
    }

    private void assertCancelPosTrxNotUsed(
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

    private Optional<PaymentCancel> findCancelByOriginal(
            String phase,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> cancelByOriginalOpt =
                cancelRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        originalPosTrx,
                        originalAttemptSeq
                );

        cancelByOriginalOpt.ifPresent(cancel ->
                log.info("[cancel][{}] cancel row found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                        phase,
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        cancel.cancelStatus()
                )
        );

        return cancelByOriginalOpt;
    }

    /**
     * PENDING cancel row insert가 실패했을 때의 경합/방어 처리.
     * <p>
     * 이 함수의 역할:
     * - insert 실패를 즉시 장애로 보지 않고, unique 제약 경합으로 기존 row가 생겼는지 재조회한다.
     * - 기존 row가 있으면 그 row의 상태를 기준으로 재응답한다.
     * - 기존 row도 없으면 정상 흐름이 아니므로 로그를 남기고 retryLater로 방어한다.
     */
    private CancelResponse handleInsertPendingMiss(
            CancelRequest request,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> rereadCancelByOriginalOpt =
                findCancelByOriginal(
                        "C5-insert-miss",
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );

        // UNIQUE 제약 경합으로 insert가 실패했으면 먼저 생성된 row를 응답 소스로 사용한다.
        // - 예: 같은 원거래 취소 요청 2개가 거의 동시에 들어온 경우.
        // - 한쪽 insert만 성공하고 다른 쪽은 여기로 내려온 뒤 기존 row를 재응답한다.
        if (rereadCancelByOriginalOpt.isPresent())
            return factory.fromExistingCancel(request, rereadCancelByOriginalOpt.get());

        // insert도 실패했고 재조회도 실패한 경우.
        // - 정상적인 unique 경합이라면 row가 보여야 하므로, 이 로그는 DB 반영/트랜잭션/매퍼 쪽 확인 신호다.
        log.error("[cancel][C5-insert-miss][CRITICAL_CANCEL_ROW_NOT_FOUND] pending insert failed but cancel row not found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                posTrx, originalPosTrx, originalAttemptSeq);

        return CancelResponse.retryLater(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );
    }

    @Transactional
    public CancelResponse finalizeCancel(
            PaymentCancelPrepareResult prepared,
            VanCancelResponse vanCancelResponse
    ) {
        if (prepared == null || vanCancelResponse == null || prepared.isCompleted())
            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    "CANCEL_FINALIZE_INVALID_PREPARE_RESULT"
            );

        String posTrx = prepared.posTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

        CancelStatus vanFinalStatus = vanCancelResponse.cancelStatus();
        String responseDeclineCode = VanDeclineCodeMapper.toCode(vanCancelResponse.declineCode());

        switch (vanFinalStatus) {
            // PENDING -> VAN도 아직 취소 결과를 확정하지 못한 상태.
            // - 이미 DB에는 PENDING row가 있으므로 추가 update 없이 retryLater 응답한다.
            case PENDING -> {
                return CancelResponse.retryLater(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );
            }

            // CANCELLED -> 취소 성공 확정.
            // - PAYMENT_CANCEL row를 CANCELLED로 바꾸고 cancelApprovalNo를 저장한다.
            // - 응답은 update RETURNING row 기준으로 조립해 DB 저장값과 응답값을 맞춘다.
            case CANCELLED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.cancelled(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        vanCancelResponse.cancelApprovalNo()
                );
                Optional<PaymentCancel> updatedCancelOpt =
                        cancelRepository.updateCancelResult(updateParam);

                if (updatedCancelOpt.isPresent()) {
                    PaymentCancel updatedCancel = updatedCancelOpt.get();

                    recorder.recordCancelEvent(
                            PaymentEventType.CANCEL_FINALIZED,
                            posTrx,
                            originalPosTrx,
                            originalAttemptSeq,
                            ResultCode.OK.name(),
                            updatedCancel.cancelStatus().name(),
                            vanCancelResponse.vanTrxId(),
                            updatedCancel.cancelApprovalNo(),
                            updatedCancel.declineCode(),
                            "cancel finalized"
                    );

                    return CancelResponse.cancelled(
                            updatedCancel.posTrx(),
                            updatedCancel.originalPosTrx(),
                            updatedCancel.originalAttemptSeq(),
                            updatedCancel.cancelApprovalNo()
                    );
                }

            }

            // CANCEL_DECLINED -> 취소 거절 확정.
            // - PAYMENT_CANCEL row를 CANCEL_DECLINED로 바꾸고 declineCode를 저장한다.
            // - 이 상태도 최종 상태이므로 이후 같은 원거래 취소 요청은 DB 재응답으로 처리한다.
            case CANCEL_DECLINED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.declined(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        responseDeclineCode
                );
                Optional<PaymentCancel> updatedCancelOpt =
                        cancelRepository.updateCancelResult(updateParam);

                if (updatedCancelOpt.isPresent()) {
                    PaymentCancel updatedCancel = updatedCancelOpt.get();

                    recorder.recordCancelEvent(
                            PaymentEventType.CANCEL_FINALIZED,
                            posTrx,
                            originalPosTrx,
                            originalAttemptSeq,
                            ResultCode.CANCEL_DECLINED.name(),
                            updatedCancel.cancelStatus().name(),
                            vanCancelResponse.vanTrxId(),
                            updatedCancel.cancelApprovalNo(),
                            updatedCancel.declineCode(),
                            "cancel finalized"
                    );

                    return CancelResponse.declined(
                            updatedCancel.posTrx(),
                            updatedCancel.originalPosTrx(),
                            updatedCancel.originalAttemptSeq(),
                            updatedCancel.declineCode()
                    );
                }

            }

        }

        // C7 update 0 rows:
        // - updateCancelResult는 PENDING row를 최종 상태로 바꾸는 단계다.
        // - 여기까지 왔다는 것은 이미 VAN cancel을 1회 호출했다는 뜻이다.
        // - update 결과가 empty라고 해서 cancel row 자체가 없다는 의미는 아니다.
        //   예: CURRENT_TRX_NO/상태 조건이 맞지 않거나, 다른 흐름이 먼저 row를 확정했을 수 있다.
        // - 그래서 즉시 retryLater로 끝내지 않고 original 기준으로 재조회해 현재 DB 상태를 응답에 반영한다.
        log.error("[cancel][C7-0rows] update cancel result failed. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, intendedStatus={}",
                posTrx, originalPosTrx, originalAttemptSeq, vanFinalStatus);
        return recoverFromC7UpdateEmpty(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

    }

    /**
     * C7 update empty 복구 처리.
     *
     * <p>
     * 이 메서드는 C5 insert miss 복구와 일부러 분리한다.
     * C5는 VAN cancel 호출 권한을 얻지 못한 중복/경합 요청이므로
     * 기존 row가 CANCELLED면 ALREADY_CANCELLED로 재응답한다.
     *
     * <p>
     * 반면 C7은 이미 이 요청이 VAN cancel을 호출한 뒤 update 결과만 empty인 상황이다.
     * 따라서 original 기준 재조회 결과가 CANCELLED이면 "이미 취소됨"이 아니라
     * "취소 성공 상태가 DB에서 확인됨"으로 보고 CANCELLED 응답을 내려준다.
     *
     * <p>
     * 복구 기준:
     * - PENDING         : DB 최종 확정이 아직 보이지 않으므로 RETRY_LATER
     * - CANCELLED       : C7 요청의 취소 성공 상태로 보고 CANCELLED
     * - CANCEL_DECLINED : C7 요청의 취소 거절 상태로 보고 CANCEL_DECLINED
     * - row 없음        : 정합성 이상 가능성이 있으므로 error log 후 RETRY_LATER
     */
    private CancelResponse recoverFromC7UpdateEmpty(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> recoveredCancelByOriginalOpt =
                findCancelByOriginal(
                        "C7-update-empty",
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );

        if (recoveredCancelByOriginalOpt.isEmpty()) {
            log.error("[cancel][C7-recovery][CRITICAL_CANCEL_ROW_NOT_FOUND] update result missed and cancel row not found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    posTrx, originalPosTrx, originalAttemptSeq);

            return CancelResponse.retryLater(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );
        }

        PaymentCancel recoveredCancelByOriginal = recoveredCancelByOriginalOpt.get();
        log.info("[cancel][C7-recovery] recovered cancel row after update miss. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, recoveredStatus={}",
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                recoveredCancelByOriginal.cancelStatus()
        );

        return factory.fromC7RecoveredCancel(recoveredCancelByOriginal);
    }

}
