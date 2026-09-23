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
 *
 * <p>전체 흐름:
 * <ol>
 *     <li>실행 가능한 Task를 claim한다.</li>
 *     <li>재claim이라면 이전 Worker의 열린 History를 OWNERSHIP_LOST로 닫는다.</li>
 *     <li>이번 실행의 새 History를 만든다.</li>
 *     <li>실행 결과에 따라 Task와 History를 함께 종료한다.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class RecoveryExecutionTransactionService {

    private final RecoveryTaskRepository taskRepository;
    private final RecoveryHistoryRepository historyRepository;

    /**
     * 실행 가능한 Task를 가져오고 이번 Worker 실행을 나타내는 새 History를 만든다.
     *
     * <p>lease가 만료된 RUNNING Task를 다시 가져온 경우에는 이전 실행의 열린 History를 먼저 닫는다.
     * 따라서 하나의 Task에는 현재 Worker의 History만 열린 상태로 남는다.
     *
     * @return 실행할 task가 없으면 empty, 있으면 같은 transaction에서 만든 task와 history
     */
    @Transactional
    public Optional<ClaimedRecoveryExecution> claimAndStart(
            String claimToken,
            LocalDateTime now,
            LocalDateTime leaseExpiresAt
    ) {
        // 1. PENDING, 실행 시각이 된 RETRY_WAIT, lease가 만료된 RUNNING 중 한 건을 claim한다.
        Optional<RecoveryTask> taskOptional = taskRepository.claimNext(claimToken, now, leaseExpiresAt);

        // 2. 실행할 Task가 없으면 History도 만들지 않고 끝낸다.
        if (taskOptional.isEmpty()) return Optional.empty();

        RecoveryTask task = taskOptional.get();

        // 3. 재claim된 Task라면 이전 Worker가 남긴 열린 History를 소유권 상실로 종료한다.
        historyRepository.finishOpenByRecoveryTaskId(
                task.id(),
                RecoveryHistoryResult.OWNERSHIP_LOST,
                "LEASE_EXPIRED_RECLAIMED",
                now
        );

        // 4. 실제 Worker 실행 횟수에 맞춰 다음 tryNo로 새 History를 시작한다.
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
        // 1. 현재 Worker가 소유한 RUNNING Task만 RESOLVED로 바꾼다.
        int updated = taskRepository.markResolved(taskId, claimToken, now);

        if (updated == 0) {
            // 2. 소유권을 잃었다면 Task는 유지하고 이번 History만 종료한다.
            finishHistory(historyId, RecoveryHistoryResult.OWNERSHIP_LOST, null, now);

            return RecoveryWorkerResultType.OWNERSHIP_LOST;
        }

        // 3. Task 전이가 성공하면 History에도 같은 최종 결과를 기록한다.
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
        // 1. 현재 Worker가 소유한 Task만 재시도 대기 상태로 전환한다.
        int updated = taskRepository.markRetryWait(taskId, claimToken, now, nextRetryAt);

        if (updated == 0) {
            // 2. 소유권을 잃은 Worker의 결과는 Task에 반영하지 않는다.
            finishHistory(historyId, RecoveryHistoryResult.OWNERSHIP_LOST, null, now);

            return RecoveryWorkerResultType.OWNERSHIP_LOST;
        }

        // 3. 다음 실행 시각과 retryCount가 저장된 뒤 History를 종료한다.
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
        // 1. 현재 Worker가 소유한 Task만 운영자 확인 대상으로 전환한다.
        int updated = taskRepository.markManualReview(taskId, claimToken, now);

        if (updated == 0) {
            // 2. 소유권을 잃었다면 상태를 강제로 바꾸지 않는다.
            finishHistory(historyId, RecoveryHistoryResult.OWNERSHIP_LOST, null, now);

            return RecoveryWorkerResultType.OWNERSHIP_LOST;
        }

        // 3. 운영자가 원인을 확인할 수 있도록 History에 errorCode를 함께 남긴다.
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
        // Task 상태는 추측해서 바꾸지 않고 이번 실행이 실패했다는 사실만 남긴다.
        finishHistory(historyId, RecoveryHistoryResult.UNKNOWN_FAILURE, errorCode, now);
    }

    /**
     * 열린 History를 한 번만 종료한다.
     *
     * <p>재claim 과정에서 이미 OWNERSHIP_LOST로 닫힌 History를 이전 Worker가 다시 닫는 경우만
     * 정상적인 중복 요청으로 허용하고, 그 밖의 update 실패는 데이터 불일치로 처리한다.
     */
    private void finishHistory(
            Long historyId,
            RecoveryHistoryResult result,
            String errorCode,
            LocalDateTime now
    ) {
        int updated = historyRepository.finish(historyId, result, errorCode, now);

        // 1. 열린 History 한 건을 정상적으로 종료했다.
        if (updated == 1) return;

        // 2. stale Worker라면 새 Worker가 같은 History를 먼저 닫았는지 확인한다.
        if (result == RecoveryHistoryResult.OWNERSHIP_LOST) {
            Optional<RecoveryHistory> current = historyRepository.findById(historyId);

            // 이미 같은 결과로 종료됐다면 원하는 상태에 도달했으므로 성공으로 본다.
            if (current.isPresent()
                    && current.get().result().equals(RecoveryHistoryResult.OWNERSHIP_LOST.name())
                    && current.get().finishedAt() != null
            ) {
                return;
            }
        }

        // 3. History가 없거나 다른 결과로 끝났다면 예상하지 못한 상태이므로 rollback한다.
        throw new IllegalStateException("Recovery history finish failed: " + historyId);
    }

}
