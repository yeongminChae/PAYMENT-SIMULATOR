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
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 취소 처리 중 DB 트랜잭션이 필요한 구간만 담당한다.
 *
 * <p>
 * 이 클래스의 핵심 역할:
 * - prepare(): 원승인 posTrx lock, 원승인/카드 검증, 기존 취소 재응답 판단, 신규 PENDING row 선점
 * - finalizeCancel(): VAN cancel 응답을 PENDING row의 최종 상태로 확정
 * - finalizeUnknownTimeout(): VAN timeout을 UNKNOWN_TIMEOUT으로 저장하고 미확정 응답 반환
 *
 * <p>
 * 외부 VAN 호출은 이 클래스 안에서 하지 않는다. prepare()가 커밋된 뒤에만 오케스트레이션 서비스가
 * VAN을 호출하고, 그 결과를 finalizeCancel()로 다시 가져온다. 이 경계를 지켜야 DB lock을 잡은 채
 * 네트워크 I/O를 수행하지 않고, 동시에 같은 원승인에 대한 중복 취소도 막을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelTransactionService {

    private final PaymentCancelRepository cancelRepository;
    private final PaymentAttemptRepository attemptRepository;
    private final CancelCardVerificationPolicy policy;
    private final CancelResponseFactory factory;
    private final CancelEventRecorder recorder;

    /**
     * VAN 취소를 호출하기 전 DB 기준으로 취소 가능 여부를 결정하고 신규 취소 row를 선점한다.
     *
     * <p>
     * 이 메서드가 completed 결과를 반환하면 이미 DB 상태만으로 응답이 확정된 경로이므로
     * 호출자는 VAN 취소를 호출하면 안 된다.
     */
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

    /**
     * 원승인 attempt가 취소 가능한 APPROVED 상태인지 검사하고, 불가하면 응답을 즉시 확정한다.
     */
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
     * 원거래 기준 기존 취소 row가 있으면 현재 요청에서는 VAN을 재호출하지 않고 기존 상태로 재응답한다.
     */
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

    /**
     * VAN cancel을 호출해도 되는 요청인지 DB row 생성 결과로 결정한다.
     *
     * <p>
     * 이 메서드는 먼저 PAYMENT_CANCEL에 PENDING row를 insert한다.
     * insert에 성공하면 이 요청이 VAN cancel을 호출해도 되는 대표 요청이므로 created 결과를 반환한다.
     *
     * <p>
     * insert에 실패하면 누군가 같은 원거래로 cancel row를 먼저 만든 상황일 수 있다.
     * 그래서 바로 오류로 끝내지 않고 DB를 다시 조회한다.
     * - 같은 원거래 row가 있으면 이미 취소 요청이 접수된 것이므로 그 row 상태를 응답한다.
     * - 그래도 row가 없으면 판단 근거가 없으므로 retryLater로 방어한다.
     *
     * <p>
     * 결과적으로 이 메서드가 created를 반환한 요청만 VAN을 호출하고,
     * completed를 반환한 요청은 VAN을 호출하지 않는다.
     */
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

    /**
     * 취소 거래번호(posTrx)가 이미 사용됐는지 검사한다.
     *
     * <p>
     * 같은 원거래 재요청이라도 cancel posTrx 자체는 1회용 거래번호이므로 재사용을 허용하지 않는다.
     */
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

    /**
     * 원거래 식별자 기준으로 PAYMENT_CANCEL row를 조회한다.
     */
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

    /**
     * VAN 취소 응답을 DB의 PENDING cancel row에 최종 상태로 반영하고 API 응답을 만든다.
     *
     * <p>
     * update는 PENDING row에만 성공해야 한다. 0 rows가 반환되면 이미 VAN은 호출된 뒤이므로,
     * 원거래 기준 재조회로 현재 DB 상태를 확인해 응답 의미를 보정한다.
     */
    @Transactional
    public CancelResponse finalizeCancel(
            PaymentCancelPrepareResult prepared,
            VanCancelResponse vanCancelResponse
    ) {
        // TX2 진입 방어.
        // - completed prepare 결과는 이미 응답이 확정된 경로라 VAN을 호출하면 안 된다.
        // - prepared/vanCancelResponse 누락은 서비스 조립 오류이므로 내부 오류로 즉시 중단한다.
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
            // - PAYMENT_CANCEL row를 CANCELLED로 바꾸고 VAN 취소 거래번호와 cancelApprovalNo를 저장한다.
            // - 응답은 update RETURNING row 기준으로 조립해 DB 저장값과 응답값을 맞춘다.
            case CANCELLED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.cancelled(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        vanCancelResponse.vanTrxId(),
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
            // - PAYMENT_CANCEL row를 CANCEL_DECLINED로 바꾸고 VAN 취소 거래번호와 declineCode를 저장한다.
            // - 이 상태도 최종 상태이므로 이후 같은 원거래 취소 요청은 DB 재응답으로 처리한다.
            case CANCEL_DECLINED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.declined(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        vanCancelResponse.vanTrxId(),
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

    /**
     * VAN 취소 호출이 timeout 된 요청을 UNKNOWN_TIMEOUT으로 확정 저장한다.
     *
     * <p>
     * timeout은 VAN이 취소를 처리했는지 알 수 없는 상태다. 따라서 CANCELLED/CANCEL_DECLINED를
     * 추측하지 않고 UNKNOWN_TIMEOUT으로 남기며, 후속 요청은 기존 row를 보고 VAN 재호출 없이 retryLater로 응답한다.
     */
    @Transactional
    public CancelResponse finalizeUnknownTimeout(
            PaymentCancelPrepareResult prepared
    ) {
        String posTrx = prepared.posTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

        CancelResultUpdateParam updateParam =
                CancelResultUpdateParam.unknownTimeout(posTrx, originalPosTrx, originalAttemptSeq);

        // PENDING row만 UNKNOWN_TIMEOUT으로 바꾼다.
        // - update가 실패하면 다른 흐름이 먼저 상태를 바꿨을 수 있으므로 C7 복구 로직과 동일하게 재조회한다.
        Optional<PaymentCancel> updated = cancelRepository.updateCancelResult(updateParam);

        return updated.isPresent()
            ? CancelResponse.retryLater(posTrx, originalPosTrx, originalAttemptSeq)
            : recoverFromC7UpdateEmpty(posTrx, originalPosTrx, originalAttemptSeq)
        ;

    }

    @Transactional
    public CancelResponse finalizeCancelInquiry(
            PaymentCancel cancel,
            VanInquiryResponse response
    ) {
        // 이 메서드는 VAN I/O 이후의 짧은 DB 확정 transaction만 담당한다.
        // UNKNOWN_TIMEOUT cancel row와 SUCCESS/CANCEL 응답만 받아 상태 전이를 좁게 제한한다.
        if (cancel == null
                || response == null
                || cancel.cancelStatus() != CancelStatus.UNKNOWN_TIMEOUT
                || response.resultCode() != VanInquiryResultCode.SUCCESS
                || response.targetType() != VanInquiryTargetType.CANCEL
                || cancel.posTrx().equals(response.targetTrxNo()) == false
                || response.targetAttemptSeq() != null
                || response.status() == null) {

            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    "CANCEL_INQUIRY_FINALIZE_INVALID"
            );
        }

        // VAN Inquiry(CANCEL) 응답 status를 PAYMENT_CANCEL 최종 상태 update 파라미터로 변환한다.
        // APPROVAL 계열 status가 섞이면 TCP boundary 검증 누락이므로 여기서도 한 번 더 차단한다.
        CancelResultUpdateParam param = switch (response.status()) {

            case CANCELLED ->
                    CancelResultUpdateParam.cancelled(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            response.vanTrxId(),
                            response.cancelApprovalNo()
                    );

            case CANCEL_DECLINED ->
                    CancelResultUpdateParam.declined(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            response.vanTrxId(),
                            VanDeclineCodeMapper.toCode(response.declineCode())
                    );

            default ->
                    throw new BusinessException(
                            ResultCode.INTERNAL_ERROR,
                            "CANCEL_INQUIRY_STATUS_INVALID"
                    );
        };

        // 기존 updateCancelResult는 PENDING 전용으로 유지한다.
        // Inquiry 복구는 UNKNOWN_TIMEOUT row에만 반영해야 일반 취소 확정 경로와 조건이 섞이지 않는다.
        Optional<PaymentCancel> updated = cancelRepository.updateUnknownTimeoutToFinal(param);

        if (updated.isPresent()) return responseFromCurrentCancel(updated.get());

        // 같은 UNKNOWN_TIMEOUT row에 동시에 inquiry가 들어오면 한 요청만 update에 성공할 수 있다.
        // update miss 시에는 cancel posTrx로 재조회해서 이미 확정된 DB 상태를 정본으로 응답한다.
        Optional<PaymentCancel> reread = cancelRepository.findByPosTrx(cancel.posTrx());

        if (reread.isEmpty()) {
            log.error(
                    "[cancel-inquiry] update missed and cancel row not found. posTrx={}",
                    cancel.posTrx()
            );

            return CancelResponse.retryLater(
                    cancel.posTrx(),
                    cancel.originalPosTrx(),
                    cancel.originalAttemptSeq()
            );
        }

        return responseFromCurrentCancel(reread.get());
    }

    /**
     * PAYMENT_CANCEL 현재 row 상태를 cancel inquiry API 응답으로 변환한다.
     *
     * <p>
     * 기존 재취소 요청에서는 CANCELLED를 ALREADY_CANCELLED로 응답하지만,
     * cancel inquiry는 저장된 최종 결과를 확인하는 API이므로 CANCELLED를 그대로 반환한다.
     */
    private CancelResponse responseFromCurrentCancel(PaymentCancel cancel) {
        return switch (cancel.cancelStatus()) {

            case CANCELLED ->
                    CancelResponse.cancelled(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            cancel.cancelApprovalNo()
                    );

            case CANCEL_DECLINED ->
                    CancelResponse.declined(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            cancel.declineCode()
                    );

            case PENDING, UNKNOWN_TIMEOUT ->
                    CancelResponse.retryLater(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq()
                    );
        };
    }

}
