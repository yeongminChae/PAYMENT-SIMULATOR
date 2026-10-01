package com.chaeyeongmin.payment_sim.payment.application.cancel.support;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelRequest;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.application.cancel.transaction.model.PaymentCancelPrepareResult;
import com.chaeyeongmin.payment_sim.payment.application.common.PaymentResultCodeMapper;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.PaymentCancel;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 원승인에 대한 취소 요청 선점을 담당한다.
 *
 * <p>기존 취소 row가 있으면 DB 상태를 재사용하고,
 * 없으면 PENDING row 생성을 시도한다.
 * PENDING 생성에 성공한 요청만 VAN cancel 호출 권한을 얻는다.
 *
 * <p>동일 원거래에 대한 동시 요청으로 insert 충돌이 발생하면
 * 기존 row를 재조회하여 중복 VAN 호출을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelReservationHandler {

    private final PaymentCancelRepository repository;
    private final CancelResponseFactory factory;
    private final CancelEventRecorder recorder;

    /**
     * 원거래 기준 기존 취소 row가 있으면 해당 상태로 응답을 확정한다.
     * 기존 row가 존재하는 요청은 VAN cancel을 다시 호출하지 않는다.
     */
    public Optional<PaymentCancelPrepareResult> completeIfExistingCancelByOriginal(
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
     * 원거래 식별자로 PAYMENT_CANCEL row를 조회한다.
     */
    private Optional<PaymentCancel> findCancelByOriginal(
            String phase,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> cancelByOriginalOpt =
                repository.findByOriginalPosTrxAndOriginalAttemptSeq(
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
     * 신규 취소를 PENDING 상태로 선점한다.
     *
     * <p>PENDING row 생성에 성공하면 VAN 호출이 가능한 created 결과를 반환한다.
     * insert 충돌 또는 실패 시 원거래 기준으로 기존 row를 재조회하여
     * 중복 요청을 복구하고 VAN 재호출을 방지한다.
     */
    public PaymentCancelPrepareResult insertPendingCancelOrRecover(
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

        // PENDING row 생성에 성공한 요청만 VAN cancel 호출 권한을 얻는다.
        // unique 충돌은 동일 원거래 요청의 경합으로 보고 기존 row 재조회로 복구한다.
        Optional<PaymentCancel> pendingCancelOpt;
        try {
            pendingCancelOpt = repository.insertPendingCancel(insertParam);

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
     * PENDING insert 실패 후 기존 취소 row를 재조회한다.
     *
     * <p>기존 row가 있으면 해당 상태로 응답하고,
     * row도 확인되지 않으면 RETRY_LATER로 방어한다.
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

}
