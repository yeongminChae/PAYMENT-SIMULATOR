package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RecoveryFinalizationServiceImpl implements RecoveryFinalizationService {

    private final PaymentAttemptRepository attemptRepository;
    private final PaymentCancelRepository cancelRepository;
    private final PaymentReversalRepository reversalRepository;

    @Override
    @Transactional
    public RecoveryFinalizeResult finalizeApproval(AttemptResultUpdateParam intended) {
        // intended가 정말 terminal target인지 검증
        if (intended.finalStatus() != PaymentFinalStatus.APPROVED
                && intended.finalStatus() != PaymentFinalStatus.DECLINED) {
            throw new IllegalArgumentException("Approval recovery target must be APPROVED or DECLINED");
        }

        Optional<PaymentAttemptUpdatedRow> updated = attemptRepository.updateRecoverableToFinal(intended);

        // 1. 내가 실제 DB terminal 확정에 성공
        if (updated.isPresent()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.APPLIED,
                    intended.finalStatus().name(),
                    updated.get().finalStatus().name()
            );
        }

        // 2. conditional update miss → DB 현재 상태 다시 조회
        Optional<PaymentAttempt> reread =
                attemptRepository.findByPosTrxAndAttemptSeq(
                        intended.posTrx(),
                        intended.attemptSeq()
                );

        // 3. 대상 자체가 사라짐
        if (reread.isEmpty()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                    intended.finalStatus().name(),
                    null
            );
        }

        PaymentAttempt attempt = reread.get();
        PaymentFinalStatus dbStatus = attempt.getFinalStatusEnum();

        // 4. 다른 thread가 이미 똑같은 terminal fact로 확정
        if (dbStatus == intended.finalStatus()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                    intended.finalStatus().name(),
                    dbStatus.name()
            );
        }

        // 5. DB가 아직 미확정 상태
        if (dbStatus == PaymentFinalStatus.PROCESSING
                || dbStatus == PaymentFinalStatus.UNKNOWN_TIMEOUT) {

            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.STILL_UNRESOLVED,
                    intended.finalStatus().name(),
                    dbStatus.name()
            );
        }

        // 6. 여기까지 왔다는 것은
        // DB도 terminal이고 intended도 terminal인데 서로 다르다는 뜻
        return new RecoveryFinalizeResult(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                intended.finalStatus().name(),
                dbStatus.name()
        );
    }

    @Override
    @Transactional
    public RecoveryFinalizeResult finalizeCancel(CancelResultUpdateParam intended) {
        // intended가 정말 terminal target인지 검증
        if (intended.cancelStatus() != CancelStatus.CANCELLED
                && intended.cancelStatus() != CancelStatus.CANCEL_DECLINED) {
            throw new IllegalArgumentException("Cancel recovery target must be CANCELLED or CANCEL_DECLINED");
        }

        Optional<PaymentCancel> updated = cancelRepository.updateRecoverableToFinal(intended);

        // 1. 내가 실제 DB terminal 확정에 성공
        if (updated.isPresent()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.APPLIED,
                    intended.cancelStatusValue(),
                    updated.get().cancelStatus().name()
            );
        }

        // 2. conditional update miss → DB 현재 상태 다시 조회
        Optional<PaymentCancel> reread = cancelRepository.findByPosTrx(intended.posTrx());

        // 3. 대상 자체가 사라짐
        if (reread.isEmpty()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                    intended.cancelStatusValue(),
                    null
            );
        }

        PaymentCancel cancel = reread.get();

        // reread 후 original identity도 확인
        if (cancel.originalPosTrx().equals(intended.originalPosTrx()) == false
                || cancel.originalAttemptSeq() != intended.originalAttemptSeq()) {
            throw new IllegalStateException("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
        }

        CancelStatus dbStatus = cancel.cancelStatus();

        // 4. 다른 thread가 이미 똑같은 terminal fact로 확정
        if (dbStatus == intended.cancelStatus()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                    intended.cancelStatusValue(),
                    dbStatus.name()
            );
        }

        // 5. DB가 아직 미확정 상태
        if (dbStatus == CancelStatus.PENDING
                || dbStatus == CancelStatus.UNKNOWN_TIMEOUT) {

            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.STILL_UNRESOLVED,
                    intended.cancelStatusValue(),
                    dbStatus.name()
            );
        }

        // 6. 여기까지 왔다는 것은
        // DB도 terminal이고 intended도 terminal인데 서로 다르다는 뜻
        return new RecoveryFinalizeResult(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                intended.cancelStatusValue(),
                dbStatus.name()
        );
    }

    @Override
    @Transactional
    public RecoveryFinalizeResult finalizeReversal(ReversalResultUpdateParam intended) {
        // intended가 정말 terminal target인지 검증
        if (intended.reversalStatus() != ReversalStatus.REVERSED
                && intended.reversalStatus() != ReversalStatus.REVERSAL_DECLINED) {
            throw new IllegalArgumentException("Reversal recovery target must be REVERSED or REVERSAL_DECLINED");
        }

        Optional<PaymentReversal> updated = reversalRepository.updateRecoverableToFinal(intended);

        // 1. 내가 실제 DB terminal 확정에 성공
        if (updated.isPresent()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.APPLIED,
                    intended.reversalStatusValue(),
                    updated.get().reversalStatus().name()
            );
        }

        // 2. conditional update miss → DB 현재 상태 다시 조회
        Optional<PaymentReversal> reread = reversalRepository.findByReversalPosTrx(intended.reversalPosTrx());

        // 3. 대상 자체가 사라짐
        if (reread.isEmpty()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                    intended.reversalStatusValue(),
                    null
            );
        }

        PaymentReversal reversal = reread.get();

        // reread 후 original identity도 확인
        if (reversal.originalPosTrx().equals(intended.originalPosTrx()) == false
                || reversal.originalAttemptSeq() != intended.originalAttemptSeq()) {
            throw new IllegalStateException("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
        }

        ReversalStatus dbStatus = reversal.reversalStatus();

        // 4. 다른 thread가 이미 똑같은 terminal fact로 확정
        if (dbStatus == intended.reversalStatus()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                    intended.reversalStatusValue(),
                    dbStatus.name()
            );
        }

        // 5. DB가 아직 미확정 상태
        if (dbStatus == ReversalStatus.PENDING) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.STILL_UNRESOLVED,
                    intended.reversalStatusValue(),
                    dbStatus.name()
            );
        }

        // 6. 여기까지 왔다는 것은
        // DB도 terminal이고 intended도 terminal인데 서로 다르다는 뜻
        return new RecoveryFinalizeResult(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                intended.reversalStatusValue(),
                dbStatus.name()
        );
    }

}
