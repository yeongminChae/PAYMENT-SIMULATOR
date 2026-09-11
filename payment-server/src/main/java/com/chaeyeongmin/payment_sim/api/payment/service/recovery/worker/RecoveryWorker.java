package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandler;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResultType;
import com.chaeyeongmin.payment_sim.common.util.IdGenerator;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 실행 가능한 Recovery Task 한 건을 claim하고 target별 Handler에 위임한다.
 *
 * <p>거래별 DB 조회, VAN 통신, finalization은 전부 Handler의 책임이다. Worker는 task lifecycle만
 * 조립한다. Handler가 RESOLVED를 반환하면 claim token과 유효한 lease로 소유권을 확인하면서 task를
 * 완료 처리하고, 나머지 Handler 결과는 Worker 결과로 변환해 호출자에게 그대로 전달한다.
 * RETRY_WAIT/MANUAL_REVIEW 전이는 후속 단계에서 추가한다.
 */
@Component
public class RecoveryWorker {

    /** Recovery Task의 claim 및 완료 상태 변경을 담당하는 저장소다. */
    private final RecoveryTaskRepository recoveryTaskRepository;

    /** targetType을 키로 사용해 claim한 task를 담당 Handler에 연결한다. */
    private final Map<RecoveryTargetType, RecoveryHandler> handlers;

    /** Worker가 task를 독점 처리할 수 있는 시간이다. */
    private final Duration leaseDuration;

    /** claim 시각과 완료 시각을 각각 읽을 수 있게 주입받는 애플리케이션 시간 기준이다. */
    private final Clock clock;

    /**
     * Worker 실행에 필요한 저장소, Handler, lease 정책, 시간 기준을 구성한다.
     *
     * <p>Handler 목록은 생성 시점에 targetType 기반 Map으로 변환한다. 따라서 실행할 때마다 목록을
     * 탐색하지 않으며, 동일 targetType의 Handler가 중복 등록된 구성 오류도 시작 단계에서 발견한다.
     *
     * @param recoveryTaskRepository task claim 및 완료 상태 변경 저장소
     * @param handlers Spring에 등록된 거래 종류별 RecoveryHandler 목록
     * @param leaseDuration 한 Worker가 claim한 task를 소유할 수 있는 시간
     * @param clock claim 시각과 완료 시각을 제공하는 시간 기준
     * @throws IllegalArgumentException leaseDuration이 0 이하인 경우
     * @throws IllegalStateException 같은 targetType의 Handler가 중복 등록된 경우
     */
    public RecoveryWorker(
            RecoveryTaskRepository recoveryTaskRepository,
            List<RecoveryHandler> handlers,
            @Value("${payment.recovery.worker.lease-duration:30s}")
            Duration leaseDuration,
            Clock clock
    ) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "Recovery worker lease duration must be positive"
            );
        }

        this.recoveryTaskRepository = recoveryTaskRepository;
        this.handlers = indexHandlers(handlers);
        this.leaseDuration = leaseDuration;
        this.clock = clock;
    }

    /**
     * 실행 가능한 task를 최대 한 건 claim해 처리한다.
     *
     * <p>처리 순서는 claim token 생성 → task 한 건 claim → Handler 위임 → 결과에 따른
     * task lifecycle 반영이다. 실행할 task가 없으면 NO_TASK를 반환하며 Handler를 호출하지 않는다.
     * Worker는 VAN 호출이나 거래별 DB 처리 방법을 알지 못한다.
     *
     * @return task ID와 Handler 상세 결과를 포함한 이번 한 건의 Worker 처리 결과
     * @throws IllegalStateException task의 targetType을 처리할 Handler가 등록되지 않은 경우
     */
    public RecoveryWorkerResult executeOne() {
        // 1. 이번 실행만의 claim token을 만든다. 이후 상태 변경 시 소유권 확인용으로 사용한다.
        String claimToken = IdGenerator.uuid();

        // 2. claim 판단의 기준 시각을 한 번만 구하고, 그 시각에서 lease 만료 시각을 계산한다.
        LocalDateTime claimedAt = LocalDateTime.now(clock);

        // 3. PENDING, 재시도 시각이 지난 RETRY_WAIT, lease가 만료된 RUNNING 중 최대 한 건을 claim한다.
        Optional<RecoveryTask> recoveryTask = recoveryTaskRepository.claimNext(claimToken, claimedAt, claimedAt.plus(leaseDuration));

        // 4. 실행 가능한 task가 없으면 Handler를 호출하지 않고 NO_TASK로 종료한다.
        if (recoveryTask.isEmpty()) {
            return new RecoveryWorkerResult(RecoveryWorkerResultType.NO_TASK, null, null);
        }

        // 5. claim에 성공했으므로 소유한 task를 꺼낸다.
        RecoveryTask task = recoveryTask.get();

        // 6. 거래 종류별 세부 복구는 Worker가 직접 처리하지 않고 해당 Handler에 위임한다.
        RecoveryHandlerResult handlerResult = handleClaimedTask(task);

        // 7. Handler가 복구 완료를 확인한 경우에만 Recovery Task 자체를 RESOLVED로 바꾼다.
        if (handlerResult.resultType() == RecoveryHandlerResultType.RESOLVED) {
            // Handler 처리 중 시간이 흘렀을 수 있으므로 완료 시점의 현재 시간을 다시 구한다.
            int updated = recoveryTaskRepository.markResolved(task.id(), claimToken, LocalDateTime.now(clock));

            // claim token과 lease가 아직 유효해 update되면 RESOLVED, 아니면 소유권 상실로 반환한다.
            return new RecoveryWorkerResult(
                    updated == 1
                            ? RecoveryWorkerResultType.RESOLVED
                            : RecoveryWorkerResultType.OWNERSHIP_LOST,
                    task.id(),
                    handlerResult
            );

        }

        // 8. 미완료 결과는 task를 변경하지 않고 동일한 의미의 Worker 결과 타입으로 변환한다.
        RecoveryWorkerResultType workerResultType = switch (handlerResult.resultType()) {
            case STILL_UNRESOLVED -> RecoveryWorkerResultType.STILL_UNRESOLVED;
            case TERMINAL_CONFLICT -> RecoveryWorkerResultType.TERMINAL_CONFLICT;
            case TARGET_NOT_FOUND -> RecoveryWorkerResultType.TARGET_NOT_FOUND;
            case RESOLVED -> throw new IllegalStateException("Unexpected RESOLVED result");
        };

        return new RecoveryWorkerResult(
                workerResultType,
                task.id(),
                handlerResult
        );

    }

    /**
     * claim한 task의 targetType에 맞는 Handler를 선택하고 거래별 복구를 위임한다.
     *
     * <p>이 메서드는 Recovery Task 상태를 변경하지 않는다. Handler가 반환한 업무 결과의 해석과
     * task lifecycle 반영은 executeOne()이 담당한다.
     *
     * @param task 이번 Worker가 claim한 Recovery Task
     * @return 선택된 Handler가 반환한 거래 복구 결과
     * @throws IllegalStateException 해당 targetType의 Handler가 등록되지 않은 경우
     */
    private RecoveryHandlerResult handleClaimedTask(RecoveryTask task) {
        // Recovery Task의 targetType을 처리하는 Handler를 찾는다.
        RecoveryHandler handler = handlers.get(task.targetType());
        if (handler == null) throw new IllegalStateException("No RecoveryHandler for target type: " + task.targetType());

        // VAN/DB 세부 복구 흐름은 Handler 내부에서 수행한다.
        return handler.handle(task);
    }

    /**
     * Spring이 수집한 Handler 목록을 targetType 기반 routing Map으로 변환한다.
     *
     * <p>하나의 targetType에 Handler가 둘 이상 등록되면 어떤 구현체를 선택해야 할지 모호하므로
     * 애플리케이션 구성 오류로 보고 즉시 실패시킨다.
     *
     * @param handlers 애플리케이션에 등록된 RecoveryHandler 목록
     * @return targetType별 Handler Map
     * @throws IllegalStateException 같은 targetType의 Handler가 중복 등록된 경우
     */
    private static Map<RecoveryTargetType, RecoveryHandler> indexHandlers(List<RecoveryHandler> handlers) {
        Map<RecoveryTargetType, RecoveryHandler> indexed = new EnumMap<>(RecoveryTargetType.class);

        for (RecoveryHandler handler : handlers) {
            // Handler 자신이 선언한 targetType을 routing key로 등록한다.
            RecoveryHandler previous = indexed.put(handler.targetType(), handler);

            // 기존 값이 있었다면 동일 targetType을 담당하는 Handler가 이미 등록된 것이다.
            if (previous != null)
                throw new IllegalStateException("Duplicate RecoveryHandler for target type: " + handler.targetType());

        }

        return Map.copyOf(indexed);
    }

}
