package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure.RecoveryFailureClassifier;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure.RecoveryFailureType;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandler;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.notification.RecoveryManualReviewNotification;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.notification.RecoveryNotificationService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.policy.RecoveryRetryPolicy;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.ClaimedRecoveryExecution;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryExecutionTransactionService;
import com.chaeyeongmin.payment_sim.common.util.IdGenerator;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import lombok.extern.slf4j.Slf4j;
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
 * <p>거래별 DB 조회, VAN 통신, 거래 원장 마무리는 Handler의 책임이다. Worker는 Handler 결과를
 * Recovery Task 상태와 실행 History에 반영한다. RESOLVED는 완료 처리하고,
 * STILL_UNRESOLVED는 retry 정책에 따라 RETRY_WAIT 또는 MANUAL_REVIEW로,
 * TERMINAL_CONFLICT/TARGET_NOT_FOUND는 MANUAL_REVIEW로 전이한다.
 * 상태를 바꿀 때는 claim token과 lease를 함께 확인해 현재 소유자만 변경할 수 있게 한다.
 * MANUAL_REVIEW 전환이 DB에 저장된 뒤에는 운영자가 확인할 수 있도록 알림을 요청한다.
 */
@Component
@Slf4j
public class RecoveryWorker {

    /** Task claim과 History 시작, Task 상태와 History 종료를 각각 짧은 transaction으로 처리한다. */
    private final RecoveryExecutionTransactionService transactionService;

    /** targetType을 키로 사용해 claim한 task를 담당 Handler에 연결한다. */
    private final Map<RecoveryTargetType, RecoveryHandler> handlers;

    /** Worker가 task를 독점 처리할 수 있는 시간이다. */
    private final Duration leaseDuration;

    /** claim 시각과 완료 시각을 각각 읽을 수 있게 주입받는 애플리케이션 시간 기준이다. */
    private final Clock clock;

    /** 미해결 task의 재시도 가능 여부와 다음 실행 시각을 정한다. */
    private final RecoveryRetryPolicy recoveryRetryPolicy;

    /** Handler 예외를 재시도, 운영자 확인, 원본 전파 중 하나로 분류한다. */
    private final RecoveryFailureClassifier recoveryFailureClassifier;

    /** DB에 MANUAL_REVIEW가 저장된 뒤 운영자에게 필요한 정보를 전달한다. */
    private final RecoveryNotificationService recoveryNotificationService;

    /**
     * Worker 실행에 필요한 저장소, Handler, lease 정책, 시간 기준을 구성한다.
     *
     * <p>Handler 목록은 생성 시점에 targetType 기반 Map으로 변환한다. 따라서 실행할 때마다 목록을
     * 탐색하지 않으며, 동일 targetType의 Handler가 중복 등록된 구성 오류도 시작 단계에서 발견한다.
     *
     * @param transactionService claim/History 시작과 Task/History 종료 transaction 경계
     * @param handlers Spring에 등록된 거래 종류별 RecoveryHandler 목록
     * @param leaseDuration 한 Worker가 claim한 task를 소유할 수 있는 시간
     * @param clock claim 시각과 완료 시각을 제공하는 시간 기준
     * @param recoveryRetryPolicy unresolved task의 retry 가능 여부와 다음 재시도 시각을 결정하는 정책
     * @param recoveryFailureClassifier Handler 예외를 retry/manual/unknown으로 분류하는 정책
     * @param recoveryNotificationService MANUAL_REVIEW 전환 완료를 알리는 서비스
     * @throws IllegalArgumentException leaseDuration이 0 이하인 경우
     * @throws IllegalStateException 같은 targetType의 Handler가 중복 등록된 경우
     */
    public RecoveryWorker(
            RecoveryExecutionTransactionService transactionService,
            List<RecoveryHandler> handlers,
            @Value("${payment.recovery.worker.lease-duration:30s}")
            Duration leaseDuration,
            Clock clock,
            RecoveryRetryPolicy recoveryRetryPolicy,
            RecoveryFailureClassifier recoveryFailureClassifier,
            RecoveryNotificationService recoveryNotificationService
    ) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Recovery worker lease duration must be positive");
        }

        this.transactionService = transactionService;
        this.handlers = indexHandlers(handlers);
        this.leaseDuration = leaseDuration;
        this.clock = clock;
        this.recoveryRetryPolicy = recoveryRetryPolicy;
        this.recoveryFailureClassifier = recoveryFailureClassifier;
        this.recoveryNotificationService = recoveryNotificationService;
    }

    /**
     * 실행 가능한 task를 최대 한 건 claim해 처리한다.
     *
     * <p>처리 순서는 claim token 생성 → Task claim과 History 시작 → Handler 위임 → 결과에 따른
     * Task와 History 종료다. 실행할 task가 없으면 NO_TASK를 반환하며 Handler를 호출하지 않는다.
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

        // 3. 실행 가능한 Task를 claim하고 같은 transaction에서 이번 실행 History도 시작한다.
        Optional<ClaimedRecoveryExecution> recoveryTask =
                transactionService.claimAndStart(claimToken, claimedAt, claimedAt.plus(leaseDuration));

        // 4. 실행 가능한 task가 없으면 Handler를 호출하지 않고 NO_TASK로 종료한다.
        if (recoveryTask.isEmpty()) {
            return new RecoveryWorkerResult(RecoveryWorkerResultType.NO_TASK, null, null);
        }

        // 5. claim한 Task와 이번 실행을 기록할 History는 이후 상태 전이에서도 항상 함께 사용한다.
        ClaimedRecoveryExecution claimed = recoveryTask.get();

        RecoveryTask task = claimed.task();
        RecoveryHistory history = claimed.history();

        // 6. 예외 분류는 Handler 선택과 실행에서 난 오류에만 적용한다.
        // 이후 transactionService에서 난 DB 오류는 아래 catch 밖에서 그대로 전파된다.
        RecoveryHandlerResult handlerResult;
        try {
            RecoveryHandler handler = getHandler(task);
            handlerResult = handler.handle(task);
        } catch (RuntimeException e) {
            return handleRecoveryFailure(task, history, claimToken, e);
        }

        return new RecoveryWorkerResult(
                applyRecoveryTransition(handlerResult, task, history, claimToken),
                task.id(),
                handlerResult
        );

    }

    /**
     * Handler가 반환한 업무 결과를 Recovery Task의 다음 상태로 반영한다.
     *
     * <p>Task 전이와 History 종료는 transactionService가 하나의 transaction으로 처리한다.
     * Task update가 0이면 lease가 끝났거나 다른 Worker가 재claim한 것이므로
     * History를 OWNERSHIP_LOST로 끝내고 그 결과를 반환한다.
     */
    private RecoveryWorkerResultType applyRecoveryTransition(
            RecoveryHandlerResult handlerResult,
            RecoveryTask task,
            RecoveryHistory history,
            String claimToken
    ) {
        switch (handlerResult.resultType()) {

            // Handler가 복구 완료를 확인한 경우에만 Recovery Task 자체를 RESOLVED로 바꾼다.
            case RESOLVED: {
                // Handler 처리 중 시간이 흘렀을 수 있으므로 완료 시점의 현재 시간을 다시 구한다.
                return transactionService.resolve(
                        task.id(),
                        history.id(),
                        claimToken,
                        LocalDateTime.now(clock)
                );
            }

            case STILL_UNRESOLVED:
                // 정상 조회 결과가 아직 미해결인 경우에는 오류 코드 없이 기존 재시도 정책을 따른다.
                return applyRetryOrManualReview(
                        task,
                        history,
                        claimToken,
                        null
                );

            case TERMINAL_CONFLICT:
                // 이미 서로 양립할 수 없는 최종 상태이므로 자동 재시도하지 않는다.
                return applyManualReview(
                        task,
                        history,
                        claimToken,
                        LocalDateTime.now(clock),
                        "TERMINAL_CONFLICT"
                );

            case TARGET_NOT_FOUND:
                // 복구 대상 자체를 찾지 못한 경우 운영자가 원거래를 확인해야 한다.
                return applyManualReview(
                        task,
                        history,
                        claimToken,
                        LocalDateTime.now(clock),
                        "TARGET_NOT_FOUND"
                );

            case OWNERSHIP_LOST:
                return RecoveryWorkerResultType.OWNERSHIP_LOST;

            default: throw new IllegalStateException("Unexpected handler result: " + handlerResult.resultType());
        }

    }

    /**
     * claim한 task의 targetType에 맞는 Handler를 선택한다.
     *
     * <p>이 메서드는 Recovery Task 상태를 변경하지 않는다. Handler가 반환한 업무 결과의 해석과
     * task lifecycle 반영은 executeOne()이 담당한다.
     *
     * @param task 이번 Worker가 claim한 Recovery Task
     * @return 해당 targetType을 처리할 Handler
     * @throws IllegalStateException 해당 targetType의 Handler가 등록되지 않은 경우
     */
    private RecoveryHandler getHandler(RecoveryTask task) {
        // Recovery Task의 targetType을 처리하는 Handler를 찾는다.
        RecoveryHandler handler = handlers.get(task.targetType());
        if (handler == null) {
            throw new IllegalStateException("No RecoveryHandler for target type: " + task.targetType());
        }

        return handler;
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
            if (previous != null) {
                throw new IllegalStateException("Duplicate RecoveryHandler for target type: " + handler.targetType());
            }

        }

        return Map.copyOf(indexed);
    }

    /**
     * Handler 예외를 분류해 task의 다음 상태를 결정한다.
     *
     * <p>RETRYABLE은 일반 미해결 결과와 같은 retry 정책을 사용하고, MANUAL_REVIEW는 즉시
     * 운영자 확인 상태로 보낸다. UNKNOWN은 History에 실패 사실만 남기고 원래 예외를 다시 던진다.
     * 이 메서드는 Handler 예외에만 사용하며 repository 예외에는 적용하지 않는다.
     */
    private RecoveryWorkerResult handleRecoveryFailure(
            RecoveryTask task,
            RecoveryHistory history,
            String claimToken,
            RuntimeException exception
    ) {
        RecoveryFailureType failureType = recoveryFailureClassifier.classify(exception);

        switch (failureType) {
            case RETRYABLE:
                return new RecoveryWorkerResult(
                        applyRetryOrManualReview(task, history, claimToken, retryableErrorCode(exception)),
                        task.id(),
                        null
                );

            case MANUAL_REVIEW:
                return new RecoveryWorkerResult(
                        applyManualReview(task, history, claimToken, LocalDateTime.now(clock), manualReviewErrorCode(exception)),
                        task.id(),
                        null
                );

            case UNKNOWN:
                // 원인을 업무 상태로 해석할 수 없으므로 Task는 RUNNING과 기존 소유권을 유지한다.
                transactionService.unknownFailure(
                        history.id(),
                        exception.getClass().getSimpleName(),
                        LocalDateTime.now(clock)
                );

                throw exception;

            default: throw new IllegalStateException("Unexpected recovery failure type: " + failureType);
        }

    }

    /**
     * Task와 History를 MANUAL_REVIEW로 저장한 뒤 운영 알림을 보낸다.
     *
     * <p>DB 저장이 끝나기 전에 알림을 보내지 않는다. 저장 결과가 MANUAL_REVIEW일 때만 알리고,
     * 소유권을 잃어 상태를 바꾸지 못했다면 알림 없이 OWNERSHIP_LOST를 반환한다.
     */
    private RecoveryWorkerResultType applyManualReview(
            RecoveryTask task,
            RecoveryHistory history,
            String claimToken,
            LocalDateTime now,
            String errorCode
    ) {
        RecoveryWorkerResultType resultType = transactionService.manualReview(
                task.id(),
                history.id(),
                claimToken,
                now,
                errorCode
        );

        // manualReview()가 정상 반환되면 Task와 History를 저장한 DB 트랜잭션도 커밋된 상태다.
        // 실제 상태가 MANUAL_REVIEW로 바뀐 경우에만 그다음 순서로 알림을 보낸다.
        if (resultType == RecoveryWorkerResultType.MANUAL_REVIEW) {
            notifyManualReview(task, errorCode, now);
        }

        return resultType;
    }

    /**
     * Task 정보를 알림 객체로 만들고 Notification Service에 전달한다.
     * 알림 중 발생한 예외는 로그만 남기고 끝내 이미 저장된 MANUAL_REVIEW 상태를 그대로 유지한다.
     */
    private void notifyManualReview(RecoveryTask task, String errorCode, LocalDateTime occurredAt) {
        RecoveryManualReviewNotification notification = new RecoveryManualReviewNotification(
                task.id(),
                task.targetType(),
                task.targetTrxNo(),
                task.targetAttemptSeq(),
                task.originalPosTrx(),
                task.originalAttemptSeq(),
                task.retryCount(),
                errorCode,
                occurredAt
        );

        try {
            recoveryNotificationService.notifyManualReview(notification);
        } catch (RuntimeException e) {
            log.error(
                    "Recovery MANUAL_REVIEW notification failed. taskId={}, errorCode={}",
                    task.id(),
                    errorCode,
                    e
            );
        }
    }

    /**
     * 남은 재시도 횟수가 있으면 RETRY_WAIT로 보내고, 모두 사용했으면 MANUAL_REVIEW로 보낸다.
     *
     * <p>정상적인 미해결 결과는 재시도 소진 시 RETRY_EXHAUSTED를 남긴다. Handler 예외로 들어온
     * errorCode가 있으면 실제 실패 원인을 잃지 않도록 재시도 소진 뒤에도 그 코드를 유지한다.
     */
    private RecoveryWorkerResultType applyRetryOrManualReview(
            RecoveryTask task,
            RecoveryHistory history,
            String claimToken,
            String errorCode
    ) {
        LocalDateTime now = LocalDateTime.now(clock);

        if (recoveryRetryPolicy.canRetry(task.retryCount())) {
            LocalDateTime nextRetryAt = recoveryRetryPolicy.nextRetryAt(task.retryCount(), now);

            return transactionService.retryWait(
                    task.id(),
                    history.id(),
                    claimToken,
                    now,
                    nextRetryAt,
                    errorCode
            );

        }

        // 예외 원인이 없던 정상 미해결 결과만 RETRY_EXHAUSTED로 기록한다.
        return applyManualReview(
                task,
                history,
                claimToken,
                now,
                errorCode != null ? errorCode : "RETRY_EXHAUSTED"
        );
    }

    /** 재시도 가능한 VAN 예외를 History에 저장할 안정적인 오류 코드로 바꾼다. */
    private String retryableErrorCode(RuntimeException exception) {
        if (exception instanceof VanGatewayTimeoutException) {
            return "VAN_GATEWAY_TIMEOUT";
        }

        if (exception instanceof VanGatewayRequestNotSentException) {
            return "VAN_GATEWAY_REQUEST_NOT_SENT";
        }

        throw new IllegalStateException("Unexpected retryable exception: " + exception.getClass().getName());
    }

    /** 데이터 불변식 위반 예외가 가진 RECOVERY_* 코드를 운영자 확인 사유로 사용한다. */
    private String manualReviewErrorCode(RuntimeException exception) {
        if (exception instanceof RecoveryInvariantViolationException) {
            String errorCode = exception.getMessage();

            // 운영자가 원인을 구분할 코드가 없다면 잘못 생성된 예외이므로 조용히 처리하지 않는다.
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalStateException("Recovery invariant violation requires error code", exception);
            }

            return errorCode;
        }

        throw new IllegalStateException("Unexpected manual review exception: " + exception.getClass().getName(), exception);
    }

}
