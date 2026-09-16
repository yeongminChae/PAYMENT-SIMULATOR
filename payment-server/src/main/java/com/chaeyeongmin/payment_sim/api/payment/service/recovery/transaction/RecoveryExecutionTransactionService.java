package com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResultType;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Recovery Task claim과 실행 시작 이력 생성을 하나의 짧은 DB transaction으로 묶는다.
 *
 * <p>History 저장이 실패하면 claim도 함께 rollback되어 task가 RUNNING으로 남지 않는다.
 * 외부 VAN 호출과 Handler 실행은 이 transaction에 포함하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RecoveryExecutionTransactionService {

    private final RecoveryTaskRepository taskRepository;
    private final RecoveryHistoryRepository historyRepository;

    /**
     * 실행 가능한 task 한 건을 claim하고 그 task의 새 tryNo로 START History를 만든다.
     *
     * @return 실행할 task가 없으면 empty, 있으면 같은 transaction에서 만든 task와 history
     */
    @Transactional
    public Optional<ClaimedRecoveryExecution> claimAndStart(
            String claimToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    ) {
        Optional<RecoveryTask> taskOptional = taskRepository.claimNext(claimToken, now, leaseExpiresAt);

        // claim하지 못했으면 실행 시도 자체가 없으므로 History도 만들지 않는다.
        if (taskOptional.isEmpty()) {
            return Optional.empty();
        }

        RecoveryTask task = taskOptional.get();
        // retryCount와 별개로 실제 Worker 실행 횟수를 이어서 기록한다.
        int tryNo = historyRepository.nextTryNo(task.id());
        RecoveryHistory history = historyRepository.insertStarted(task.id(), tryNo, now);

        return Optional.of(new ClaimedRecoveryExecution(task, history));
    }

    /** Task와 실행 이력을 같은 transaction에서 RESOLVED로 끝낸다. */
    @Transactional
    public RecoveryWorkerResultType resolve(
            Long taskId,
            Long historyId,
            String claimToken,
            LocalDateTime now
    ) {
        int updated = taskRepository.markResolved(taskId, claimToken, now);

        if (updated == 0) {
            finishHistory(historyId, RecoveryHistoryResult.OWNERSHIP_LOST, null, now);

            return RecoveryWorkerResultType.OWNERSHIP_LOST;
        }

        finishHistory(historyId, RecoveryHistoryResult.RESOLVED, null, now);

        return RecoveryWorkerResultType.RESOLVED;
    }

    /** Task를 재시도 대기로 보내고 같은 transaction에서 실행 이력을 끝낸다. */
    @Transactional
    public RecoveryWorkerResultType retryWait(
            Long taskId,
            Long historyId,
            String claimToken,
            LocalDateTime now,
            LocalDateTime nextRetryAt,
            String errorCode
    ) {
        int updated = taskRepository.markRetryWait(taskId, claimToken, now, nextRetryAt);

        if (updated == 0) {
            finishHistory(historyId, RecoveryHistoryResult.OWNERSHIP_LOST, null, now);

            return RecoveryWorkerResultType.OWNERSHIP_LOST;
        }

        finishHistory(historyId, RecoveryHistoryResult.RETRY_WAIT, errorCode, now);

        return RecoveryWorkerResultType.RETRY_WAIT;
    }

    /** Task를 운영자 확인 대상으로 보내고 같은 transaction에서 실행 이력을 끝낸다. */
    @Transactional
    public RecoveryWorkerResultType manualReview(
            Long taskId,
            Long historyId,
            String claimToken,
            LocalDateTime now,
            String errorCode
    ) {
        int updated = taskRepository.markManualReview(taskId, claimToken, now);

        if (updated == 0) {
            finishHistory(historyId, RecoveryHistoryResult.OWNERSHIP_LOST, null, now);

            return RecoveryWorkerResultType.OWNERSHIP_LOST;
        }

        finishHistory(historyId, RecoveryHistoryResult.MANUAL_REVIEW, errorCode, now);

        return RecoveryWorkerResultType.MANUAL_REVIEW;
    }

    /** 알 수 없는 Handler 실패의 이력만 끝내고 RUNNING Task는 그대로 둔다. */
    @Transactional
    public void unknownFailure(
            Long historyId,
            String errorCode,
            LocalDateTime now
    ) {
        finishHistory(historyId, RecoveryHistoryResult.UNKNOWN_FAILURE, errorCode, now);
    }

    private void finishHistory(
            Long historyId,
            RecoveryHistoryResult result,
            String errorCode,
            LocalDateTime now
    ) {
        int updated = historyRepository.finish(historyId, result, errorCode, now);

        if (updated != 1) {
            throw new IllegalStateException("Recovery history finish failed: " + historyId);
        }
    }

}
