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
 * 한 번의 Recovery 실행에서 Task 상태와 실행 이력이 서로 어긋나지 않도록 transaction을 관리한다.
 *
 * <p>실행 전에는 Task claim과 History 시작을 함께 저장하고, 실행 후에는 Task 상태 변경과
 * History 종료를 함께 저장한다. 어느 한쪽이 실패하면 둘 다 rollback된다.
 * 외부 VAN 호출과 Handler 실행은 이 transaction 밖에서 수행해 DB transaction을 오래 잡지 않는다.
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
        if (taskOptional.isEmpty()) return Optional.empty();

        RecoveryTask task = taskOptional.get();

        /*
         * 새 Worker가 Task를 claim했다는 것은,
         * 이전 실행에서 아직 FINISHED_AT이 NULL인 History가 있다면
         * 더 이상 정상 완료될 수 없는 stale 실행 이력이라는 뜻이다.
         *
         * 새 실행 History를 만들기 전에 기존 open History를
         * OWNERSHIP_LOST로 종료한다.
         */
        historyRepository.finishOpenByRecoveryTaskId(
                task.id(),
                RecoveryHistoryResult.OWNERSHIP_LOST,
                "LEASE_EXPIRED_RECLAIMED",
                now
        );

        // retryCount와 별개로 실제 Worker 실행 횟수를 이어서 기록한다.
        int tryNo = historyRepository.nextTryNo(task.id());
        RecoveryHistory history = historyRepository.insertStarted(task.id(), tryNo, now);

        return Optional.of(new ClaimedRecoveryExecution(task, history));
    }

    /**
     * 복구가 완료된 Task와 이번 실행 이력을 함께 RESOLVED로 끝낸다.
     *
     * <p>claimToken이나 lease가 더 이상 유효하지 않으면 Task는 건드리지 않고,
     * 이번 History만 OWNERSHIP_LOST로 끝내 stale Worker의 결과였음을 남긴다.
     */
    @Transactional
    public RecoveryWorkerResultType resolve(
            Long taskId,
            Long historyId,
            String claimToken,
            LocalDateTime now
    ) {
        int updated = taskRepository.markResolved(taskId, claimToken, now);

        if (updated == 0) {
            // Handler 결과가 맞더라도 Task 소유권을 잃은 Worker는 상태를 확정할 수 없다.
            finishHistory(historyId, RecoveryHistoryResult.OWNERSHIP_LOST, null, now);

            return RecoveryWorkerResultType.OWNERSHIP_LOST;
        }

        finishHistory(historyId, RecoveryHistoryResult.RESOLVED, null, now);

        return RecoveryWorkerResultType.RESOLVED;
    }

    /**
     * 아직 해결되지 않은 Task를 다음 실행 시각까지 RETRY_WAIT로 보내고 History도 함께 끝낸다.
     *
     * <p>Task 상태 변경 과정에서 retryCount 증가와 nextRetryAt 저장이 함께 이뤄진다.
     * 소유권을 잃었다면 Task는 그대로 두고 History만 OWNERSHIP_LOST로 기록한다.
     */
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

    /**
     * 자동 복구를 더 진행할 수 없는 Task를 MANUAL_REVIEW로 보내고 History도 함께 끝낸다.
     *
     * <p>errorCode에는 재시도 소진, 거래 충돌, 응답 규칙 위반처럼 운영자가 확인할 이유를 남긴다.
     * 소유권을 잃었다면 Task 상태를 강제로 바꾸지 않는다.
     */
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

    /**
     * 예상하지 못한 Handler 실패를 History에 남기고 RUNNING Task와 소유권은 그대로 둔다.
     *
     * <p>UNKNOWN 오류는 업무 상태를 추측해 바꾸지 않고 상위 시스템에 예외를 알리기 위한 경로다.
     */
    @Transactional
    public void unknownFailure(
            Long historyId,
            String errorCode,
            LocalDateTime now
    ) {
        finishHistory(historyId, RecoveryHistoryResult.UNKNOWN_FAILURE, errorCode, now);
    }

    /** History가 정확히 한 번만 종료됐는지 확인하며 최종 결과를 기록한다. */
    private void finishHistory(
            Long historyId,
            RecoveryHistoryResult result,
            String errorCode,
            LocalDateTime now
    ) {
        int updated = historyRepository.finish(historyId, result, errorCode, now);

        if (updated == 1) return;

        /*
         * 새 Worker가 expired RUNNING Task를 reclaim하면서
         * 이전 Worker의 open History를 이미 OWNERSHIP_LOST로 종료했을 수 있다.
         *
         * 이후 stale Worker가 늦게 돌아와 같은 History를
         * OWNERSHIP_LOST로 다시 종료하려는 경우는 정상적인 중복 종료이므로 허용한다.
         */
        if (result == RecoveryHistoryResult.OWNERSHIP_LOST) {
            Optional<RecoveryHistory> current = historyRepository.findById(historyId);

            if (current.isPresent()
                    && current.get().result().equals(RecoveryHistoryResult.OWNERSHIP_LOST.name())
                    && current.get().finishedAt() != null
            ) {
                return;
            }
        }

        /*
         * History가 없거나,
         * OWNERSHIP_LOST가 아닌 다른 결과로 이미 종료된 경우는
         * 예상하지 못한 상태이므로 숨기지 않고 실패시킨다.
         */
        throw new IllegalStateException("Recovery history finish failed: " + historyId);
    }

}
