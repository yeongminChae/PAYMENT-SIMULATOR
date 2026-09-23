package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Recovery Handler가 VAN Inquiry로 확인한 terminal 사실을 Payment Ledger에 안전하게 반영한다.
 *
 * <p>Recovery Worker는 VAN 외부 I/O 동안 DB transaction을 유지하지 않는다.
 * 따라서 VAN 응답을 받은 뒤 Finalization transaction에서 Recovery Task를 다시 조회하고,
 * 현재 Worker가 여전히 해당 Task의 유효한 owner인지 확인한 뒤에만 Ledger를 변경해야 한다.
 *
 * <p>Approval finalization에서는 PAYMENT_RECOVERY_TASK를 FOR UPDATE로 잠근 상태에서
 * RUNNING 여부, claimToken 일치 여부, lease 유효 여부를 확인한다.
 * 이 ownership 검증과 PAYMENT_ATTEMPT conditional update는 동일 transaction에서 수행되어
 * lease 만료 후 다른 Worker가 Task를 reclaim한 상황에서 stale Worker가 Ledger를 변경하는 것을 막는다.
 *
 * <p>Ledger conditional update가 실패한 경우 현재 DB 상태를 다시 읽어
 * 이미 동일 terminal 상태로 수렴했는지, 여전히 unresolved인지,
 * 또는 서로 다른 terminal 상태가 충돌하는지를 판정한다.
 *
 * <p>현재 ownership fencing은 Approval부터 적용 중이며,
 * Cancel/Reversal에도 동일한 방식으로 확장할 예정이다.
 */
@Service
@RequiredArgsConstructor
public class RecoveryFinalizationServiceImpl implements RecoveryFinalizationService {

    private final PaymentAttemptRepository attemptRepository;
    private final PaymentCancelRepository cancelRepository;
    private final PaymentReversalRepository reversalRepository;
    private final RecoveryTaskRepository recoveryTaskRepository;
    private final Clock clock;

    @Override
    @Transactional
    public RecoveryFinalizeResult finalizeApproval(
            RecoveryTask task,
            AttemptResultUpdateParam intended
    ) {
        // intended가 정말 terminal target인지 검증
        if (intended.finalStatus() != PaymentFinalStatus.APPROVED
                && intended.finalStatus() != PaymentFinalStatus.DECLINED) {
            throw new IllegalArgumentException("Approval recovery target must be APPROVED or DECLINED");
        }


        /*
         * VAN Inquiry가 끝나는 동안 lease가 만료돼 다른 Worker가 Task를 reclaim했을 수 있다.
         *
         * 따라서 PAYMENT_ATTEMPT를 변경하기 전에 Recovery Task를 FOR UPDATE로 다시 잠그고
         * 현재 Worker의 claimToken과 lease가 아직 유효한지 확인한다.
         *
         * ownership을 잃었다면 stale Worker이므로 Ledger를 절대 변경하지 않는다.
         */
        if (hasValidOwnership(task) == false) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.OWNERSHIP_LOST,
                    intended.finalStatus().name(),
                    null
            );
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
    public RecoveryFinalizeResult finalizeCancel(
            RecoveryTask task,
            CancelResultUpdateParam intended
    ) {
        // intended가 정말 terminal target인지 검증
        if (intended.cancelStatus() != CancelStatus.CANCELLED
                && intended.cancelStatus() != CancelStatus.CANCEL_DECLINED) {
            throw new IllegalArgumentException("Cancel recovery target must be CANCELLED or CANCEL_DECLINED");
        }

        /*
         * VAN Inquiry가 끝나는 동안 lease가 만료돼
         * 다른 Worker가 Recovery Task를 reclaim했을 수 있다.
         * 현재 Worker가 더 이상 Task owner가 아니라면 PAYMENT_CANCEL Ledger를 변경하면 안 된다.
         */
        if (hasValidOwnership(task) == false) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.OWNERSHIP_LOST,
                    intended.cancelStatusValue(),
                    null
            );
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
    public RecoveryFinalizeResult finalizeReversal(
            RecoveryTask task,
            ReversalResultUpdateParam intended
    ) {
        // intended가 정말 terminal target인지 검증
        if (intended.reversalStatus() != ReversalStatus.REVERSED
                && intended.reversalStatus() != ReversalStatus.REVERSAL_DECLINED) {
            throw new IllegalArgumentException("Reversal recovery target must be REVERSED or REVERSAL_DECLINED");
        }

        /*
         * VAN Inquiry 중 lease가 만료되어 다른 Worker가 reclaim했을 수 있다.
         * 현재 Worker가 owner가 아니라면 PAYMENT_REVERSAL을 변경하지 않는다.
         */
        if (hasValidOwnership(task) == false) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.OWNERSHIP_LOST,
                    intended.reversalStatusValue(),
                    null
            );
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

    /**
     * Finalization 시점에 현재 Worker가 Recovery Task의 유효한 owner인지 확인한다.
     *
     * <p>Task row를 FOR UPDATE로 조회하므로 이 메서드가 반환된 뒤 Ledger update가 끝날 때까지
     * 다른 Worker는 동일 Task를 reclaim할 수 없다.
     *
     * <p>유효한 ownership 조건:
     * - Task가 존재함
     * - RECOVERY_STATUS가 RUNNING
     * - DB의 claimToken과 Worker가 claim 당시 받은 claimToken이 동일
     * - lease가 존재하고 현재 시각보다 이후까지 유효함
     *
     * @return 현재 Worker가 Ledger를 변경할 권한이 있으면 true,
     *         lease 만료 또는 다른 Worker reclaim 등으로 소유권을 잃었으면 false
     */
    private boolean hasValidOwnership(RecoveryTask claimedTask) {
        LocalDateTime now = LocalDateTime.now(clock);

        /*
         * 단순 조회가 아니라 FOR UPDATE 조회다.
         * ownership 확인 직후 다른 Worker가 reclaim하는 race를 막기 위해
         * Finalization transaction이 끝날 때까지 Task row lock을 유지한다.
         */
        Optional<RecoveryTask> currentTask =
                recoveryTaskRepository.findByIdForUpdate(claimedTask.id());

        if (currentTask.isEmpty()) return false;

        RecoveryTask current = currentTask.get();

        return current.recoveryStatus() == RecoveryStatus.RUNNING
                && Objects.equals(current.claimToken(), claimedTask.claimToken())
                && current.leaseExpiresAt() != null
                && current.leaseExpiresAt().isAfter(now);
    }

}
