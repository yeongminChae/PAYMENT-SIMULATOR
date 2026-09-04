package com.chaeyeongmin.payment_sim.api.payment.service.transaction;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.ReversalRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ReversalResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentReversalPrepareResult;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * reversal 처리 중 DB 트랜잭션이 필요한 구간만 담당한다.
 *
 * <p>
 * 이 클래스의 핵심 역할:
 * - prepare(): 원승인 posTrx lock, reversal 거래번호 충돌 검증, 기존 reversal 재응답 판단, 신규 PENDING row 선점
 * - finalizeReversal(): VAN reversal 응답을 PENDING row의 최종 상태로 확정
 * - cleanupPendingAndRetryLater(): VAN에 요청이 전송되지 않은 경우 PENDING row를 정리하고 재시도를 허용
 *
 * <p>
 * 외부 VAN 호출은 이 클래스 안에서 하지 않는다. prepare()가 커밋된 뒤에만 오케스트레이션 서비스가
 * VAN을 호출하고, 그 결과를 finalizeReversal()로 다시 가져온다. 이 경계를 지켜야 DB lock을 잡은 채
 * 네트워크 I/O를 수행하지 않고, 동시에 같은 원승인에 대한 중복 reversal도 막을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReversalTransactionService {

    private final PaymentReversalRepository reversalRepository;
    private final PaymentAttemptRepository attemptRepository;

    /**
     * VAN reversal을 호출하기 전 DB 기준으로 reversal 가능 여부를 결정하고 신규 reversal row를 선점한다.
     *
     * <p>
     * 이 메서드가 completed 결과를 반환하면 이미 DB 상태만으로 응답이 확정된 경로이므로
     * 호출자는 VAN reversal을 호출하면 안 된다.
     */
    @Transactional
    public PaymentReversalPrepareResult prepare(ReversalRequest request) {
        String reversalPosTrx = request.reversalPosTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        // R2-1: reversalPosTrx 빠른 사전검사.
        // - 같은 reversal 거래번호가 다른 원거래 payload로 이미 사용됐으면 lock을 기다리지 않고 곧바로 차단한다.
        // - 단, lock 대기 중 다른 요청이 같은 reversalPosTrx를 만들 수 있으므로 lock 획득 뒤 한 번 더 확인한다.
        assertReversalPosTrxNotUsedForDifferentPayload(reversalPosTrx, originalPosTrx, originalAttemptSeq);

        // R3-0: 원승인 posTrx 기준 직렬화 lock 획득.
        // - reversalPosTrx는 요청마다 다를 수 있으므로 같은 원승인에 대한 중복 reversal 판단의 lock key가 될 수 없다.
        // - 같은 원승인에 대한 reversal 판단은 이 lock 이후의 DB 재조회 결과만 신뢰한다.
        acquireOriginalPosTrxLock(originalPosTrx);

        // R2-2: lock 이후 reversalPosTrx 최종검사.
        // - 기다리는 동안 앞선 요청이 만든 reversal row까지 반영해 거래번호 재사용 여부를 다시 판단한다.
        // - 같은 reversalPosTrx + 같은 original이면 아래 기존 row 재응답 경로에서 처리한다.
        assertReversalPosTrxNotUsedForDifferentPayload(reversalPosTrx, originalPosTrx, originalAttemptSeq);

        // R4-1: 현재 reversal 거래번호 기준 기존 row 확인.
        // - 동일 reversalPosTrx가 같은 payload로 다시 들어온 경우 VAN을 재호출하지 않고 DB 상태를 재응답한다.
        Optional<PaymentReversal> existingByCurrent = reversalRepository.findByReversalPosTrx(reversalPosTrx);
        if (existingByCurrent.isPresent()) {
            return PaymentReversalPrepareResult.completed(fromExistingCurrent(existingByCurrent.get()));
        }

        // R3: reversal 대상 원승인 attempt 조회.
        // - reversal은 UNKNOWN_TIMEOUT으로 확정된 원승인 attempt를 복구/취소하는 후속 거래다.
        // - 원거래가 없으면 reversal 가능 여부도 판단할 수 없으므로 NOT_FOUND로 종료한다.
        PaymentAttempt originalAttempt = findOriginalAttemptOrThrow(originalPosTrx, originalAttemptSeq);

        // R4-2: 원거래 상태 검증.
        // - reversal 가능한 원거래는 UNKNOWN_TIMEOUT뿐이다.
        // - APPROVED/DECLINED/PROCESSING은 이 API가 임의로 되돌릴 대상이 아니다.
        // - 이 결과는 DB의 reversal 상태가 아니라 "응답 전용 reversal 불가 상태"라 PENDING row를 만들지 않는다.
        if (originalAttempt.getFinalStatusEnum() != PaymentFinalStatus.UNKNOWN_TIMEOUT) {
            return PaymentReversalPrepareResult.completed(
                    ReversalResponse.reversalNotAllowed(
                            reversalPosTrx,
                            originalPosTrx,
                            originalAttemptSeq,
                            "ORIGINAL_NOT_REVERSIBLE"
                    )
            );
        }

        // R4-3: 원거래 기준 기존 reversal row 확인.
        // - 원거래가 UNKNOWN_TIMEOUT이어도 이미 reversal 요청이 있었으면 VAN을 다시 호출하면 안 된다.
        // - 기존 row가 있으면 현재 요청의 reversalPosTrx가 달라도 원거래 기준 기존 reversal 상태를 우선한다.
        Optional<PaymentReversal> existingByOriginal =
                reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(originalPosTrx, originalAttemptSeq);
        if (existingByOriginal.isPresent()) {
            return PaymentReversalPrepareResult.completed(fromExistingOriginal(
                    reversalPosTrx,
                    existingByOriginal.get()
            ));
        }

        return insertPendingReversalOrRecover(request, originalAttempt);
    }

    /**
     * VAN reversal 응답을 DB의 PENDING reversal row에 최종 상태로 반영하고 API 응답을 만든다.
     *
     * <p>
     * update는 PENDING row에만 성공해야 한다. 0 rows가 반환되면 이미 VAN은 호출된 뒤이므로,
     * 현재 reversal 거래번호 기준 재조회로 실제 DB 상태를 확인해 응답 의미를 보정한다.
     */
    @Transactional
    public ReversalResponse finalizeReversal(
            PaymentReversalPrepareResult prepared,
            VanReversalResponse vanResponse
    ) {
        // TX2 진입 방어.
        // - completed prepare 결과는 이미 응답이 확정된 경로라 VAN을 호출하면 안 된다.
        // - prepared/vanResponse 누락은 서비스 조립 오류이므로 내부 오류로 즉시 중단한다.
        if (prepared == null || prepared.isCompleted() || vanResponse == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "REVERSAL_FINALIZE_INVALID_PREPARE_RESULT");
        }

        String reversalPosTrx = prepared.reversalPosTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

        // R7-1: VAN reversal 응답을 PAYMENT_REVERSAL update 파라미터로 변환한다.
        // - REVERSED는 reversal 승인번호를 저장하고, REVERSAL_DECLINED는 declineCode를 저장한다.
        // - 응답은 VAN 원문이 아니라 update RETURNING으로 받은 DB 저장값 기준으로 조립한다.
        ReversalResultUpdateParam updateParam = switch (vanResponse.reversalStatus()) {
            case REVERSED -> ReversalResultUpdateParam.reversed(
                    reversalPosTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    vanResponse.vanReversalTrxId(),
                    vanResponse.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResultUpdateParam.declined(
                    reversalPosTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    vanResponse.vanReversalTrxId(),
                    code(vanResponse.declineCode())
            );
        };

        // R7-2: PENDING -> 최종 reversal 상태 조건부 update.
        // - updateReversalResult는 아직 PENDING인 row만 최종 상태로 바꾸는 멱등성 보호 장치다.
        // - update miss가 나면 다른 흐름이 먼저 상태를 바꿨거나 row 조건이 기대와 달라졌을 수 있다.
        Optional<PaymentReversal> updated = reversalRepository.updateReversalResult(updateParam);
        return updated.isPresent()
                ? fromFinalizedCurrent(updated.get())
                : recoverFromFinalizeUpdateMiss(reversalPosTrx, originalPosTrx, originalAttemptSeq);
    }

    /**
     * VAN에 reversal 요청이 전송되지 않은 경우 방금 만든 PENDING row를 정리한다.
     *
     * <p>
     * Socket connect 실패처럼 request bytes가 나가지 않은 경우에만 사용해야 한다.
     * VAN이 요청을 받지 않았으므로 PENDING row를 삭제해 동일 payload 재시도를 허용한다.
     */
    @Transactional
    public ReversalResponse cleanupPendingAndRetryLater(PaymentReversalPrepareResult prepared) {
        // cleanup 진입 방어.
        // - completed prepare 결과는 이 메서드의 대상이 아니다.
        // - PENDING row를 만든 created 결과에서 VAN request-not-sent가 확인된 경우에만 정리한다.
        if (prepared == null || prepared.isCompleted()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "REVERSAL_CLEANUP_INVALID_PREPARE_RESULT");
        }

        // R6-1: request not sent 복구.
        // - VAN에 전달되지 않은 요청이므로 내부 PENDING row만 삭제한다.
        // - timeout처럼 전달 여부가 불명확한 경우에는 삭제하면 중복 reversal 위험이 생긴다.
        reversalRepository.deletePendingReversal(
                prepared.reversalPosTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq()
        );

        return ReversalResponse.retryLater(
                prepared.reversalPosTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq()
        );
    }

    /**
     * 같은 원승인에 대한 reversal 판단을 직렬화하기 위해 원승인 거래번호 기준 lock을 잡는다.
     */
    private void acquireOriginalPosTrxLock(String originalPosTrx) {
        Optional<Integer> lockResult = attemptRepository.acquireExistingPosTrxLock(originalPosTrx);
        if (lockResult.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "ORIGINAL_PAYMENT_ATTEMPT_NOT_FOUND");
        }
    }

    /**
     * reversal 대상 원승인 attempt를 조회하고, 존재하지 않으면 reversal row 생성 없이 NOT_FOUND로 중단한다.
     */
    private PaymentAttempt findOriginalAttemptOrThrow(String originalPosTrx, int originalAttemptSeq) {
        return attemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq)
                .orElseThrow(() -> new BusinessException(
                        ResultCode.NOT_FOUND,
                        "ORIGINAL_PAYMENT_ATTEMPT_NOT_FOUND"
                ));
    }

    /**
     * reversal 거래번호가 다른 payload에 이미 사용됐는지 검사한다.
     *
     * <p>
     * 같은 reversalPosTrx + 같은 original은 멱등 재요청으로 보고 허용한다. 다른 original이면
     * 거래번호 재사용이므로 외부 VAN 호출 전에 차단한다.
     */
    private void assertReversalPosTrxNotUsedForDifferentPayload(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentReversal> existing = reversalRepository.findByReversalPosTrx(reversalPosTrx);
        if (existing.isEmpty()) return;

        PaymentReversal reversal = existing.get();
        if (reversal.originalPosTrx().equals(originalPosTrx)
                && reversal.originalAttemptSeq() == originalAttemptSeq) {
            return;
        }

        throw new BusinessException(ResultCode.CONFLICT, "POS_TRX_ALREADY_USED");
    }

    /**
     * VAN reversal을 호출해도 되는 요청인지 DB row 생성 결과로 결정한다.
     *
     * <p>
     * 이 메서드는 먼저 PAYMENT_REVERSAL에 PENDING row를 insert한다.
     * insert에 성공하면 이 요청이 VAN reversal을 호출해도 되는 대표 요청이므로 created 결과를 반환한다.
     *
     * <p>
     * insert에 실패하면 누군가 같은 reversalPosTrx 또는 같은 원거래로 row를 먼저 만든 상황일 수 있다.
     * 그래서 바로 오류로 끝내지 않고 DB를 다시 조회한다.
     * - 같은 reversalPosTrx + 같은 원거래 row가 있으면 같은 요청 재시도로 보고 그 row 상태를 응답한다.
     * - 같은 reversalPosTrx + 다른 원거래 row가 있으면 거래번호 재사용이므로 CONFLICT로 막는다.
     * - 같은 원거래 row가 있으면 이미 다른 reversal 요청이 접수된 것이므로 그 row 상태를 응답한다.
     * - 그래도 row가 없으면 판단 근거가 없으므로 retryLater로 방어한다.
     *
     * <p>
     * 결과적으로 이 메서드가 created를 반환한 요청만 VAN을 호출하고,
     * completed를 반환한 요청은 VAN을 호출하지 않는다.
     */
    private PaymentReversalPrepareResult insertPendingReversalOrRecover(
            ReversalRequest request,
            PaymentAttempt originalAttempt
    ) {
        // R5: PENDING reversal row 생성.
        // - PAYMENT_REVERSAL에는 이번 reversal 거래번호와 원거래 식별자, 원승인 금액을 저장한다.
        // - insert가 성공한 요청만 VAN reversal 호출 권한을 얻는다.
        // - unique 충돌은 같은 current 또는 같은 original reversal이 먼저 접수된 경합으로 보고 재조회 복구로 넘긴다.
        try {
            Optional<PaymentReversal> inserted = reversalRepository.insertPendingReversal(
                    ReversalInsertParam.pending(
                            request.reversalPosTrx(),
                            request.originalPosTrx(),
                            request.originalAttemptSeq(),
                            originalAttempt.amount()
                    )
            );

            if (inserted.isPresent()) {
                // R5 성공.
                // - 호출자는 이 created 결과의 원승인 attempt 정보로 VAN reversal 요청을 구성한다.
                return PaymentReversalPrepareResult.created(
                        request.reversalPosTrx(),
                        request.originalPosTrx(),
                        request.originalAttemptSeq(),
                        originalAttempt
                );
            }

        } catch (DataIntegrityViolationException e) {
            // SQLite/MyBatis 조합에서는 unique 충돌이 Optional.empty가 아니라
            // DataIntegrityViolationException 계열 예외로 올라올 수 있다.
            // 이 경로에서는 VAN reversal을 호출하지 않고, 이미 생성된 PAYMENT_REVERSAL row를 재조회해 재응답한다.
            log.warn("[reversal][prepare] pending insert conflict. reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    request.reversalPosTrx(),
                    request.originalPosTrx(),
                    request.originalAttemptSeq(),
                    e
            );
        }

        // R5 insert miss 복구 1: 현재 reversal 거래번호 기준 재조회.
        // - 같은 reversalPosTrx가 같은 원거래에 쓰인 row면 같은 요청 재시도로 보고 그 row 상태를 재응답한다.
        // - 같은 reversalPosTrx가 다른 원거래에 쓰인 row면 거래번호 재사용이므로 CONFLICT로 차단한다.
        Optional<PaymentReversal> existingByCurrent = reversalRepository.findByReversalPosTrx(request.reversalPosTrx());
        if (existingByCurrent.isPresent()) {
            PaymentReversal existing = existingByCurrent.get();
            if (existing.originalPosTrx().equals(request.originalPosTrx())
                    && existing.originalAttemptSeq() == request.originalAttemptSeq()) {
                return PaymentReversalPrepareResult.completed(fromExistingCurrent(existing));
            }

            throw new BusinessException(ResultCode.CONFLICT, "POS_TRX_ALREADY_USED");
        }

        // R5 insert miss 복구 2: 원거래 기준 재조회.
        // - 같은 원승인에 대해 다른 reversalPosTrx 요청이 먼저 PENDING row를 만들었을 수 있다.
        // - 이 경우 현재 요청은 VAN을 호출하지 않고 기존 original 기준 상태를 응답한다.
        Optional<PaymentReversal> existingByOriginal =
                reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        request.originalPosTrx(),
                        request.originalAttemptSeq()
                );
        if (existingByOriginal.isPresent()) {
            return PaymentReversalPrepareResult.completed(fromExistingOriginal(
                    request.reversalPosTrx(),
                    existingByOriginal.get()
            ));
        }

        // insert도 실패했고 재조회도 실패한 경우.
        // - 정상적인 unique 경합이라면 row가 보여야 하므로, 이 경로는 retryLater로 방어한다.
        return PaymentReversalPrepareResult.completed(
                ReversalResponse.retryLater(
                        request.reversalPosTrx(),
                        request.originalPosTrx(),
                        request.originalAttemptSeq()
                )
        );
    }

    /**
     * R7 update empty 복구 처리.
     *
     * <p>
     * 이미 VAN reversal 응답을 받은 뒤 update 결과만 empty인 상황이다. 따라서 즉시 retryLater로 끝내지 않고
     * 현재 reversal 거래번호 기준으로 재조회해 실제 저장된 상태를 응답 소스로 사용한다.
     */
    private ReversalResponse recoverFromFinalizeUpdateMiss(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        // R7 update 0 rows 후 재조회.
        // - 다른 요청이 먼저 확정했거나 PENDING 조건이 더 이상 맞지 않을 수 있다.
        // - 외부 VAN 응답보다 DB에 실제 저장된 값을 우선한다.
        Optional<PaymentReversal> reread = reversalRepository.findByReversalPosTrx(reversalPosTrx);
        if (reread.isPresent()) return fromFinalizedCurrent(reread.get());

        // row 자체가 사라진 경우.
        // - VAN 응답은 받았지만 DB에 확정 저장된 상태를 확인할 수 없으므로 결과를 단정하지 않는다.
        return ReversalResponse.retryLater(reversalPosTrx, originalPosTrx, originalAttemptSeq);
    }

    /**
     * 같은 reversalPosTrx 재요청에 대해 현재 PAYMENT_REVERSAL row 기준 응답을 만든다.
     */
    private ReversalResponse fromExistingCurrent(PaymentReversal reversal) {
        return switch (reversal.reversalStatus()) {
            case PENDING -> ReversalResponse.retryLater(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq()
            );
            case REVERSED -> ReversalResponse.reversed(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResponse.declined(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.declineCode()
            );
        };
    }

    /**
     * 같은 원승인에 이미 reversal row가 있을 때 현재 요청의 reversalPosTrx로 응답을 만든다.
     *
     * <p>
     * 기존 row가 REVERSED이면 현재 요청은 신규 성공이 아니라 이미 reversal된 원거래에 대한 재요청이므로
     * ALREADY_REVERSED로 응답한다.
     */
    private ReversalResponse fromExistingOriginal(String currentReversalPosTrx, PaymentReversal reversal) {
        return switch (reversal.reversalStatus()) {
            case PENDING -> ReversalResponse.retryLater(
                    currentReversalPosTrx,
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq()
            );
            case REVERSED -> ReversalResponse.alreadyReversed(
                    currentReversalPosTrx,
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResponse.declined(
                    currentReversalPosTrx,
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.declineCode()
            );
        };
    }

    /**
     * finalize 이후 현재 reversal row 기준 응답을 만든다.
     *
     * <p>
     * 이 경로는 현재 요청이 VAN reversal을 호출한 뒤의 응답이므로 REVERSED를 ALREADY_REVERSED로 바꾸지 않는다.
     */
    private ReversalResponse fromFinalizedCurrent(PaymentReversal reversal) {
        return switch (reversal.reversalStatus()) {
            case PENDING -> ReversalResponse.retryLater(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq()
            );
            case REVERSED -> ReversalResponse.reversed(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResponse.declined(
                    reversal.reversalPosTrx(),
                    reversal.originalPosTrx(),
                    reversal.originalAttemptSeq(),
                    reversal.declineCode()
            );
        };
    }

    private String code(com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode declineCode) {
        return declineCode == null ? null : declineCode.name();
    }
}
