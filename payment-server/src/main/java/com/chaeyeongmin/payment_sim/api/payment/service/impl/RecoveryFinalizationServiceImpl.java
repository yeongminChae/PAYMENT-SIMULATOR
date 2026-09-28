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
 * 승인·취소·망취소 복구 결과를 각 결제 원장에 안전하게 반영한다.
 *
 * <p>VAN을 조회하는 동안 Recovery Task의 lease가 끝나거나 다른 Worker가 Task를 가져갈 수 있다.
 * 그래서 원장을 변경하기 직전에 Task를 잠그고, 현재 Worker가 여전히 작업 주인인지 확인한다.
 * 소유권이 유효할 때만 원장을 변경하므로 늦게 돌아온 Worker가 새 Worker의 작업을 덮어쓰지 못한다.
 *
 * <p>원장 갱신에 실패하면 현재 상태를 다시 읽고, 이미 같은 결과로 끝났는지,
 * 아직 미확정인지, 서로 다른 최종 결과가 충돌하는지 구분해 반환한다.
 */
@Service
@RequiredArgsConstructor
public class RecoveryFinalizationServiceImpl implements RecoveryFinalizationService {

    private final PaymentAttemptRepository attemptRepository;
    private final PaymentCancelRepository cancelRepository;
    private final PaymentReversalRepository reversalRepository;
    private final RecoveryTaskRepository recoveryTaskRepository;
    private final Clock clock;

    /**
     * VAN에서 확인한 승인 결과를 PAYMENT_ATTEMPT에 반영한다.
     * 작업 소유권이 없으면 승인 원장을 변경하지 않고 OWNERSHIP_LOST를 반환한다.
     */
    @Override
    @Transactional
    public RecoveryFinalizeResult finalizeApproval(
            RecoveryTask task,
            AttemptResultUpdateParam intended
    ) {
        // 복구로 확정할 수 있는 승인 최종 상태만 허용한다.
        if (intended.finalStatus() != PaymentFinalStatus.APPROVED
                && intended.finalStatus() != PaymentFinalStatus.DECLINED) {
            throw new IllegalArgumentException("Approval recovery target must be APPROVED or DECLINED");
        }


        // VAN 조회 사이에 소유권이 바뀌었을 수 있으므로 원장 갱신 직전에 다시 확인한다.
        if (hasValidOwnership(task) == false) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.OWNERSHIP_LOST,
                    intended.finalStatus().name(),
                    null
            );
        }

        Optional<PaymentAttemptUpdatedRow> updated = attemptRepository.updateRecoverableToFinal(intended);

        // 아직 미확정인 승인 건을 최종 상태로 바꾼다.
        if (updated.isPresent()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.APPLIED,
                    intended.finalStatus().name(),
                    updated.get().finalStatus().name()
            );
        }

        // 갱신하지 못했다면 다른 흐름이 먼저 처리했는지 현재 상태를 다시 확인한다.
        Optional<PaymentAttempt> reread =
                attemptRepository.findByPosTrxAndAttemptSeq(
                        intended.posTrx(),
                        intended.attemptSeq()
                );

        // 복구할 승인 건 자체가 없다.
        if (reread.isEmpty()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                    intended.finalStatus().name(),
                    null
            );
        }

        PaymentAttempt attempt = reread.get();
        PaymentFinalStatus dbStatus = attempt.getFinalStatusEnum();

        // 다른 흐름이 이미 같은 최종 상태로 처리했다.
        if (dbStatus == intended.finalStatus()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                    intended.finalStatus().name(),
                    dbStatus.name()
            );
        }

        // 갱신은 실패했지만 DB는 아직 미확정 상태다.
        if (dbStatus == PaymentFinalStatus.PROCESSING
                || dbStatus == PaymentFinalStatus.UNKNOWN_TIMEOUT) {

            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.STILL_UNRESOLVED,
                    intended.finalStatus().name(),
                    dbStatus.name()
            );
        }

        // DB의 최종 상태와 VAN에서 확인한 최종 상태가 서로 다르다.
        return new RecoveryFinalizeResult(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                intended.finalStatus().name(),
                dbStatus.name()
        );
    }

    /**
     * VAN에서 확인한 취소 결과를 PAYMENT_CANCEL에 반영한다.
     * 작업 소유권이 없으면 취소 원장을 변경하지 않고 OWNERSHIP_LOST를 반환한다.
     */
    @Override
    @Transactional
    public RecoveryFinalizeResult finalizeCancel(
            RecoveryTask task,
            CancelResultUpdateParam intended
    ) {
        // 복구로 확정할 수 있는 취소 최종 상태만 허용한다.
        if (intended.cancelStatus() != CancelStatus.CANCELLED
                && intended.cancelStatus() != CancelStatus.CANCEL_DECLINED) {
            throw new IllegalArgumentException("Cancel recovery target must be CANCELLED or CANCEL_DECLINED");
        }

        // VAN 조회 사이에 소유권이 바뀌었을 수 있으므로 원장 갱신 직전에 다시 확인한다.
        if (hasValidOwnership(task) == false) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.OWNERSHIP_LOST,
                    intended.cancelStatusValue(),
                    null
            );
        }

        Optional<PaymentCancel> updated = cancelRepository.updateRecoverableToFinal(intended);

        // 아직 미확정인 취소 건을 최종 상태로 바꾼다.
        if (updated.isPresent()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.APPLIED,
                    intended.cancelStatusValue(),
                    updated.get().cancelStatus().name()
            );
        }

        // 갱신하지 못했다면 다른 흐름이 먼저 처리했는지 현재 상태를 다시 확인한다.
        Optional<PaymentCancel> reread = cancelRepository.findByPosTrx(intended.posTrx());

        // 복구할 취소 건 자체가 없다.
        if (reread.isEmpty()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                    intended.cancelStatusValue(),
                    null
            );
        }

        PaymentCancel cancel = reread.get();

        // 같은 취소 거래번호가 맞더라도 원승인 정보가 다르면 잘못된 거래다.
        if (cancel.originalPosTrx().equals(intended.originalPosTrx()) == false
                || cancel.originalAttemptSeq() != intended.originalAttemptSeq()) {
            throw new IllegalStateException("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
        }

        CancelStatus dbStatus = cancel.cancelStatus();

        // 다른 흐름이 이미 같은 최종 상태로 처리했다.
        if (dbStatus == intended.cancelStatus()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                    intended.cancelStatusValue(),
                    dbStatus.name()
            );
        }

        // 갱신은 실패했지만 DB는 아직 미확정 상태다.
        if (dbStatus == CancelStatus.PENDING
                || dbStatus == CancelStatus.UNKNOWN_TIMEOUT) {

            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.STILL_UNRESOLVED,
                    intended.cancelStatusValue(),
                    dbStatus.name()
            );
        }

        // DB의 최종 상태와 VAN에서 확인한 최종 상태가 서로 다르다.
        return new RecoveryFinalizeResult(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                intended.cancelStatusValue(),
                dbStatus.name()
        );
    }

    /**
     * VAN에서 확인한 망취소 결과를 PAYMENT_REVERSAL에 반영한다.
     * 작업 소유권이 없으면 망취소 원장을 변경하지 않고 OWNERSHIP_LOST를 반환한다.
     */
    @Override
    @Transactional
    public RecoveryFinalizeResult finalizeReversal(
            RecoveryTask task,
            ReversalResultUpdateParam intended
    ) {
        // 복구로 확정할 수 있는 망취소 최종 상태만 허용한다.
        if (intended.reversalStatus() != ReversalStatus.REVERSED
                && intended.reversalStatus() != ReversalStatus.REVERSAL_DECLINED) {
            throw new IllegalArgumentException("Reversal recovery target must be REVERSED or REVERSAL_DECLINED");
        }

        // VAN 조회 사이에 소유권이 바뀌었을 수 있으므로 원장 갱신 직전에 다시 확인한다.
        if (hasValidOwnership(task) == false) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.OWNERSHIP_LOST,
                    intended.reversalStatusValue(),
                    null
            );
        }

        Optional<PaymentReversal> updated = reversalRepository.updateRecoverableToFinal(intended);

        // 아직 미확정인 망취소 건을 최종 상태로 바꾼다.
        if (updated.isPresent()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.APPLIED,
                    intended.reversalStatusValue(),
                    updated.get().reversalStatus().name()
            );
        }

        // 갱신하지 못했다면 다른 흐름이 먼저 처리했는지 현재 상태를 다시 확인한다.
        Optional<PaymentReversal> reread = reversalRepository.findByReversalPosTrx(intended.reversalPosTrx());

        // 복구할 망취소 건 자체가 없다.
        if (reread.isEmpty()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                    intended.reversalStatusValue(),
                    null
            );
        }

        PaymentReversal reversal = reread.get();

        // 같은 망취소 거래번호가 맞더라도 원승인 정보가 다르면 잘못된 거래다.
        if (reversal.originalPosTrx().equals(intended.originalPosTrx()) == false
                || reversal.originalAttemptSeq() != intended.originalAttemptSeq()) {
            throw new IllegalStateException("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
        }

        ReversalStatus dbStatus = reversal.reversalStatus();

        // 다른 흐름이 이미 같은 최종 상태로 처리했다.
        if (dbStatus == intended.reversalStatus()) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                    intended.reversalStatusValue(),
                    dbStatus.name()
            );
        }

        // 갱신은 실패했지만 DB는 아직 미확정 상태다.
        if (dbStatus == ReversalStatus.PENDING) {
            return new RecoveryFinalizeResult(
                    RecoveryFinalizeResultType.STILL_UNRESOLVED,
                    intended.reversalStatusValue(),
                    dbStatus.name()
            );
        }

        // DB의 최종 상태와 VAN에서 확인한 최종 상태가 서로 다르다.
        return new RecoveryFinalizeResult(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                intended.reversalStatusValue(),
                dbStatus.name()
        );
    }

    /**
     * 현재 Worker가 이 Task의 작업 주인인지 확인한다.
     *
     * <p>Task 행을 잠근 채 검사하므로 원장 갱신이 끝날 때까지 다른 Worker가 같은 Task를 가져갈 수 없다.
     * RUNNING 상태이고, claimToken이 같고, lease가 남아 있어야 유효한 소유권으로 본다.
     */
    private boolean hasValidOwnership(RecoveryTask claimedTask) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 확인 직후 소유권이 바뀌지 않도록 현재 transaction이 끝날 때까지 Task 행을 잠근다.
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
