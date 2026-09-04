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

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReversalTransactionService {

    private final PaymentReversalRepository reversalRepository;
    private final PaymentAttemptRepository attemptRepository;

    @Transactional
    public PaymentReversalPrepareResult prepare(ReversalRequest request) {
        String reversalPosTrx = request.reversalPosTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        assertReversalPosTrxNotUsedForDifferentPayload(reversalPosTrx, originalPosTrx, originalAttemptSeq);
        acquireOriginalPosTrxLock(originalPosTrx);
        assertReversalPosTrxNotUsedForDifferentPayload(reversalPosTrx, originalPosTrx, originalAttemptSeq);

        Optional<PaymentReversal> existingByCurrent =
                reversalRepository.findByReversalPosTrx(reversalPosTrx);
        if (existingByCurrent.isPresent()) {
            return PaymentReversalPrepareResult.completed(fromExistingCurrent(existingByCurrent.get()));
        }

        PaymentAttempt originalAttempt = findOriginalAttemptOrThrow(originalPosTrx, originalAttemptSeq);

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

    @Transactional
    public ReversalResponse finalizeReversal(
            PaymentReversalPrepareResult prepared,
            VanReversalResponse vanResponse
    ) {
        if (prepared == null || prepared.isCompleted() || vanResponse == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "REVERSAL_FINALIZE_INVALID_PREPARE_RESULT");
        }

        String reversalPosTrx = prepared.reversalPosTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

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

        Optional<PaymentReversal> updated = reversalRepository.updateReversalResult(updateParam);
        return updated.isPresent()
                ? fromFinalizedCurrent(updated.get())
                : recoverFromFinalizeUpdateMiss(reversalPosTrx, originalPosTrx, originalAttemptSeq);
    }

    @Transactional
    public ReversalResponse cleanupPendingAndRetryLater(PaymentReversalPrepareResult prepared) {
        if (prepared == null || prepared.isCompleted()) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "REVERSAL_CLEANUP_INVALID_PREPARE_RESULT");
        }

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

    private void acquireOriginalPosTrxLock(String originalPosTrx) {
        Optional<Integer> lockResult = attemptRepository.acquireExistingPosTrxLock(originalPosTrx);
        if (lockResult.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "ORIGINAL_PAYMENT_ATTEMPT_NOT_FOUND");
        }
    }

    private PaymentAttempt findOriginalAttemptOrThrow(String originalPosTrx, int originalAttemptSeq) {
        return attemptRepository.findByPosTrxAndAttemptSeq(originalPosTrx, originalAttemptSeq)
                .orElseThrow(() -> new BusinessException(
                        ResultCode.NOT_FOUND,
                        "ORIGINAL_PAYMENT_ATTEMPT_NOT_FOUND"
                ));
    }

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

    private PaymentReversalPrepareResult insertPendingReversalOrRecover(
            ReversalRequest request,
            PaymentAttempt originalAttempt
    ) {
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
                return PaymentReversalPrepareResult.created(
                        request.reversalPosTrx(),
                        request.originalPosTrx(),
                        request.originalAttemptSeq(),
                        originalAttempt
                );
            }

        } catch (DataIntegrityViolationException e) {
            log.warn("[reversal][prepare] pending insert conflict. reversalPosTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    request.reversalPosTrx(),
                    request.originalPosTrx(),
                    request.originalAttemptSeq(),
                    e
            );
        }

        Optional<PaymentReversal> existingByCurrent =
                reversalRepository.findByReversalPosTrx(request.reversalPosTrx());
        if (existingByCurrent.isPresent()) {
            PaymentReversal existing = existingByCurrent.get();
            if (existing.originalPosTrx().equals(request.originalPosTrx())
                    && existing.originalAttemptSeq() == request.originalAttemptSeq()) {
                return PaymentReversalPrepareResult.completed(fromExistingCurrent(existing));
            }
            throw new BusinessException(ResultCode.CONFLICT, "POS_TRX_ALREADY_USED");
        }

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

        return PaymentReversalPrepareResult.completed(
                ReversalResponse.retryLater(
                        request.reversalPosTrx(),
                        request.originalPosTrx(),
                        request.originalAttemptSeq()
                )
        );
    }

    private ReversalResponse recoverFromFinalizeUpdateMiss(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentReversal> reread = reversalRepository.findByReversalPosTrx(reversalPosTrx);
        if (reread.isPresent()) return fromFinalizedCurrent(reread.get());

        return ReversalResponse.retryLater(reversalPosTrx, originalPosTrx, originalAttemptSeq);
    }

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
