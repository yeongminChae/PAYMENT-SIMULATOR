package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure.RecoveryFailureClassifier;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure.RecoveryFailureType;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandler;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResultType;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryRetryPolicy;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryWorkerTest {

    private static final long TASK_ID = 1L;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 9, 11, 10, 0, 0);
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 9, 11, 10, 0, 31);
    private static final Instant CLAIMED_INSTANT = CLAIMED_AT.atZone(ZONE_ID).toInstant();
    private static final Instant COMPLETED_INSTANT = COMPLETED_AT.atZone(ZONE_ID).toInstant();

    private RecoveryTaskRepository recoveryTaskRepository;
    private RecoveryHandler approvalHandler;
    private RecoveryHandler cancelHandler;
    private Clock clock;
    private RecoveryWorker worker;
    private RecoveryRetryPolicy recoveryRetryPolicy;
    private RecoveryFailureClassifier recoveryFailureClassifier;

    @BeforeEach
    void setUp() {
        recoveryTaskRepository = mock(RecoveryTaskRepository.class);
        approvalHandler = handler(RecoveryTargetType.APPROVAL);
        cancelHandler = handler(RecoveryTargetType.CANCEL);
        clock = mock(Clock.class);
        recoveryRetryPolicy = mock(RecoveryRetryPolicy.class);
        recoveryFailureClassifier = mock(RecoveryFailureClassifier.class);

        when(clock.getZone()).thenReturn(ZONE_ID);
        when(clock.instant()).thenReturn(CLAIMED_INSTANT, COMPLETED_INSTANT);

        worker = new RecoveryWorker(
                recoveryTaskRepository,
                List.of(approvalHandler, cancelHandler),
                LEASE_DURATION,
                clock,
                recoveryRetryPolicy,
                recoveryFailureClassifier
        );
    }

    @Test
    @DisplayName("claim할 task가 없으면 NO_TASK이고 Handler를 호출하지 않는다")
    void executeOne_noTask_shouldReturnNoTask_withoutHandlerCall() {
        when(recoveryTaskRepository.claimNext(anyString(), eq(CLAIMED_AT), eq(CLAIMED_AT.plus(LEASE_DURATION))))
                .thenReturn(Optional.empty());

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.NO_TASK);
        assertThat(result.taskId()).isNull();
        assertThat(result.handlerResult()).isNull();
        verify(approvalHandler, never()).handle(any());
        verify(cancelHandler, never()).handle(any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
        verify(recoveryTaskRepository, never()).markRetryWait(any(), anyString(), any(), any());
        verify(recoveryTaskRepository, never()).markManualReview(any(), anyString(), any());
    }

    @Test
    @DisplayName("APPROVAL task는 Approval Handler로 routing한다")
    void executeOne_approvalTask_shouldRouteToApprovalHandler() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryRetryPolicy.canRetry(task.retryCount())).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(task.retryCount(), COMPLETED_AT))
                .thenReturn(COMPLETED_AT.plusMinutes(1));
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(COMPLETED_AT.plusMinutes(1))))
                .thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
        verify(approvalHandler).handle(task);
        verify(cancelHandler, never()).handle(any());
    }

    @Test
    @DisplayName("CANCEL task는 Cancel Handler로 routing한다")
    void executeOne_cancelTask_shouldRouteToCancelHandler() {
        RecoveryTask task = task(RecoveryTargetType.CANCEL);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        givenClaimedTask(task);
        when(cancelHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryRetryPolicy.canRetry(task.retryCount())).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(task.retryCount(), COMPLETED_AT))
                .thenReturn(COMPLETED_AT.plusMinutes(1));
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(COMPLETED_AT.plusMinutes(1))))
                .thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
        verify(cancelHandler).handle(task);
        verify(approvalHandler, never()).handle(any());
    }

    @Test
    @DisplayName("Handler가 RESOLVED이고 markResolved가 1이면 Worker도 RESOLVED다")
    void executeOne_resolvedAndUpdated_shouldReturnResolved() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RecoveryHandlerResult handlerResult = resolvedResult();
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryTaskRepository.markResolved(eq(TASK_ID), anyString(), eq(COMPLETED_AT))).thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RESOLVED);
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
    }

    @Test
    @DisplayName("Handler가 RESOLVED여도 markResolved가 0이면 OWNERSHIP_LOST다")
    void executeOne_resolvedButNotUpdated_shouldReturnOwnershipLost() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RecoveryHandlerResult handlerResult = resolvedResult();
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryTaskRepository.markResolved(eq(TASK_ID), anyString(), eq(COMPLETED_AT))).thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
    }

    @Test
    @DisplayName("retryCount 0의 STILL_UNRESOLVED는 정책 시각으로 RETRY_WAIT 전이한다")
    void executeOne_retryCountZero_shouldMarkRetryWaitWithCompletionTimeAndPolicyResult() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(1);
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryRetryPolicy.canRetry(0)).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(0, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt))).thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        ArgumentCaptor<String> claimTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(recoveryTaskRepository).claimNext(
                claimTokenCaptor.capture(),
                eq(CLAIMED_AT),
                eq(CLAIMED_AT.plus(LEASE_DURATION))
        );
        verify(recoveryRetryPolicy).canRetry(0);
        verify(recoveryRetryPolicy).nextRetryAt(0, COMPLETED_AT);
        verify(recoveryTaskRepository).markRetryWait(
                TASK_ID,
                claimTokenCaptor.getValue(),
                COMPLETED_AT,
                nextRetryAt
        );
        verify(recoveryTaskRepository, never()).markManualReview(any(), anyString(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("retryCount 1의 STILL_UNRESOLVED는 완료 시각 기준 5분 뒤 RETRY_WAIT 전이한다")
    void executeOne_retryCountOne_shouldUseFiveMinuteRetryTime() {
        assertRetryWaitTransition(1, COMPLETED_AT.plusMinutes(5));
    }

    @Test
    @DisplayName("retryCount 2의 STILL_UNRESOLVED는 완료 시각 기준 30분 뒤 RETRY_WAIT 전이한다")
    void executeOne_retryCountTwo_shouldUseThirtyMinuteRetryTime() {
        assertRetryWaitTransition(2, COMPLETED_AT.plusMinutes(30));
    }

    @Test
    @DisplayName("RETRY_WAIT 상태 전이에 실패하면 OWNERSHIP_LOST다")
    void executeOne_retryWaitUpdateZero_shouldReturnOwnershipLost() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(1);
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryRetryPolicy.canRetry(0)).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(0, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt))).thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verify(recoveryTaskRepository, never()).markManualReview(any(), anyString(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("retry exhausted STILL_UNRESOLVED는 retry 계산 없이 MANUAL_REVIEW 전이한다")
    void executeOne_retryExhausted_shouldMarkManualReview() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 3);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryRetryPolicy.canRetry(3)).thenReturn(false);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verify(recoveryRetryPolicy).canRetry(3);
        verify(recoveryRetryPolicy, never()).nextRetryAt(anyInt(), any());
        verify(recoveryTaskRepository, never()).markRetryWait(any(), anyString(), any(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
        verifyManualReviewUsesClaimTokenAndCompletionTime();
    }

    @Test
    @DisplayName("retry exhausted의 MANUAL_REVIEW 전이에 실패하면 OWNERSHIP_LOST다")
    void executeOne_retryExhaustedUpdateZero_shouldReturnOwnershipLost() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 3);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryRetryPolicy.canRetry(3)).thenReturn(false);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verify(recoveryRetryPolicy, never()).nextRetryAt(anyInt(), any());
        verify(recoveryTaskRepository, never()).markRetryWait(any(), anyString(), any(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("TERMINAL_CONFLICT는 retry 정책 없이 MANUAL_REVIEW 전이하고 원인을 보존한다")
    void executeOne_terminalConflict_shouldMarkManualReviewAndPreserveHandlerResult() {
        RecoveryHandlerResult handlerResult =
                new RecoveryHandlerResult(RecoveryHandlerResultType.TERMINAL_CONFLICT, "APPROVED", "DECLINED");
        givenApprovalHandlerResult(handlerResult);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verifyNoRetryOrResolvedTransition();
        verifyManualReviewUsesClaimTokenAndCompletionTime();
    }

    @Test
    @DisplayName("TERMINAL_CONFLICT의 MANUAL_REVIEW 전이에 실패하면 OWNERSHIP_LOST다")
    void executeOne_terminalConflictUpdateZero_shouldReturnOwnershipLost() {
        RecoveryHandlerResult handlerResult =
                new RecoveryHandlerResult(RecoveryHandlerResultType.TERMINAL_CONFLICT, "APPROVED", "DECLINED");
        givenApprovalHandlerResult(handlerResult);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verifyNoRetryOrResolvedTransition();
    }

    @Test
    @DisplayName("TARGET_NOT_FOUND는 retry 정책 없이 MANUAL_REVIEW 전이하고 원인을 보존한다")
    void executeOne_targetNotFound_shouldMarkManualReviewAndPreserveHandlerResult() {
        RecoveryHandlerResult handlerResult =
                new RecoveryHandlerResult(RecoveryHandlerResultType.TARGET_NOT_FOUND, null, null);
        givenApprovalHandlerResult(handlerResult);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verifyNoRetryOrResolvedTransition();
        verifyManualReviewUsesClaimTokenAndCompletionTime();
    }

    @Test
    @DisplayName("TARGET_NOT_FOUND의 MANUAL_REVIEW 전이에 실패하면 OWNERSHIP_LOST다")
    void executeOne_targetNotFoundUpdateZero_shouldReturnOwnershipLost() {
        RecoveryHandlerResult handlerResult =
                new RecoveryHandlerResult(RecoveryHandlerResultType.TARGET_NOT_FOUND, null, null);
        givenApprovalHandlerResult(handlerResult);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verifyNoRetryOrResolvedTransition();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("RETRYABLE Handler 예외는 retryCount 0/1/2에서 RETRY_WAIT 전이한다")
    void executeOne_retryableHandlerException_shouldMarkRetryWait(int retryCount) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, retryCount);
        RuntimeException exception = new RuntimeException("retryable");
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(retryCount + 1L);
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(recoveryFailureClassifier.classify(exception)).thenReturn(RecoveryFailureType.RETRYABLE);
        when(recoveryRetryPolicy.canRetry(retryCount)).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(retryCount, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt))).thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.handlerResult()).isNull();
        verify(recoveryTaskRepository, never()).markManualReview(any(), anyString(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("RETRYABLE Handler 예외는 retryCount 3에서 MANUAL_REVIEW 전이한다")
    void executeOne_retryableHandlerExceptionAtRetryLimit_shouldMarkManualReview() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 3);
        RuntimeException exception = new RuntimeException("retryable");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(recoveryFailureClassifier.classify(exception)).thenReturn(RecoveryFailureType.RETRYABLE);
        when(recoveryRetryPolicy.canRetry(3)).thenReturn(false);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT))).thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isNull();
        verify(recoveryTaskRepository, never()).markRetryWait(any(), anyString(), any(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("RETRYABLE 예외의 RETRY_WAIT update가 0이면 OWNERSHIP_LOST다")
    void executeOne_retryableHandlerExceptionRetryUpdateZero_shouldReturnOwnershipLost() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RuntimeException exception = new RuntimeException("retryable");
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(1);
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(recoveryFailureClassifier.classify(exception)).thenReturn(RecoveryFailureType.RETRYABLE);
        when(recoveryRetryPolicy.canRetry(0)).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(0, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt))).thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.handlerResult()).isNull();
    }

    @Test
    @DisplayName("RETRYABLE 예외의 retry exhausted update가 0이면 OWNERSHIP_LOST다")
    void executeOne_retryableHandlerExceptionManualReviewUpdateZero_shouldReturnOwnershipLost() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 3);
        RuntimeException exception = new RuntimeException("retryable");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(recoveryFailureClassifier.classify(exception)).thenReturn(RecoveryFailureType.RETRYABLE);
        when(recoveryRetryPolicy.canRetry(3)).thenReturn(false);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT))).thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.handlerResult()).isNull();
    }

    @Test
    @DisplayName("MANUAL_REVIEW Handler 예외는 MANUAL_REVIEW 전이한다")
    void executeOne_manualReviewHandlerException_shouldMarkManualReview() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RuntimeException exception = new RuntimeException("manual review");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(recoveryFailureClassifier.classify(exception)).thenReturn(RecoveryFailureType.MANUAL_REVIEW);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT))).thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isNull();
        verifyNoRetryOrResolvedTransition();
    }

    @Test
    @DisplayName("MANUAL_REVIEW 예외의 update가 0이면 OWNERSHIP_LOST다")
    void executeOne_manualReviewHandlerExceptionUpdateZero_shouldReturnOwnershipLost() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RuntimeException exception = new RuntimeException("manual review");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(recoveryFailureClassifier.classify(exception)).thenReturn(RecoveryFailureType.MANUAL_REVIEW);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT))).thenReturn(0);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.handlerResult()).isNull();
        verifyNoRetryOrResolvedTransition();
    }

    @Test
    @DisplayName("UNKNOWN Handler 예외는 원본 그대로 전파하고 transition하지 않는다")
    void executeOne_unknownHandlerException_shouldRethrowOriginalWithoutTransition() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RuntimeException exception = new RuntimeException("unknown");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(recoveryFailureClassifier.classify(exception)).thenReturn(RecoveryFailureType.UNKNOWN);

        assertThatThrownBy(worker::executeOne).isSameAs(exception);

        verify(recoveryTaskRepository, never()).markRetryWait(any(), anyString(), any(), any());
        verify(recoveryTaskRepository, never()).markManualReview(any(), anyString(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("markResolved RuntimeException은 분류하지 않고 그대로 전파한다")
    void executeOne_markResolvedException_shouldPropagateWithoutClassification() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RuntimeException exception = new RuntimeException("mark resolved failed");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(resolvedResult());
        when(recoveryTaskRepository.markResolved(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenThrow(exception);

        assertThatThrownBy(worker::executeOne).isSameAs(exception);

        verify(recoveryFailureClassifier, never()).classify(any());
    }

    @Test
    @DisplayName("markRetryWait RuntimeException은 분류하지 않고 그대로 전파한다")
    void executeOne_markRetryWaitException_shouldPropagateWithoutClassification() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(1);
        RuntimeException exception = new RuntimeException("mark retry wait failed");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(stillUnresolvedResult());
        when(recoveryRetryPolicy.canRetry(0)).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(0, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt))).thenThrow(exception);

        assertThatThrownBy(worker::executeOne).isSameAs(exception);

        verify(recoveryFailureClassifier, never()).classify(any());
    }

    @Test
    @DisplayName("markManualReview RuntimeException은 분류하지 않고 그대로 전파한다")
    void executeOne_markManualReviewException_shouldPropagateWithoutClassification() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 3);
        RuntimeException exception = new RuntimeException("mark manual review failed");
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(stillUnresolvedResult());
        when(recoveryRetryPolicy.canRetry(3)).thenReturn(false);
        when(recoveryTaskRepository.markManualReview(eq(TASK_ID), anyString(), eq(COMPLETED_AT)))
                .thenThrow(exception);

        assertThatThrownBy(worker::executeOne).isSameAs(exception);

        verify(recoveryFailureClassifier, never()).classify(any());
    }

    @Test
    @DisplayName("등록되지 않은 targetType의 task는 명시적으로 실패한다")
    void executeOne_unregisteredTargetType_shouldFail() {
        RecoveryTask task = task(RecoveryTargetType.REVERSAL);
        givenClaimedTask(task);

        assertThatThrownBy(worker::executeOne)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No RecoveryHandler for target type: REVERSAL");

        verify(approvalHandler, never()).handle(any());
        verify(cancelHandler, never()).handle(any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("같은 targetType의 Handler가 두 개면 생성자에서 실패한다")
    void constructor_duplicateHandler_shouldFail() {
        RecoveryHandler duplicateApprovalHandler = handler(RecoveryTargetType.APPROVAL);

        assertThatThrownBy(() -> new RecoveryWorker(
                recoveryTaskRepository,
                List.of(approvalHandler, duplicateApprovalHandler),
                LEASE_DURATION,
                clock,
                recoveryRetryPolicy,
                recoveryFailureClassifier
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate RecoveryHandler for target type: APPROVAL");
    }

    @ParameterizedTest(name = "leaseDuration={0}")
    @MethodSource("invalidLeaseDurations")
    @DisplayName("leaseDuration이 0 이하면 생성자에서 실패한다")
    void constructor_nonPositiveLeaseDuration_shouldFail(Duration invalidLeaseDuration) {
        assertThatThrownBy(() -> new RecoveryWorker(
                recoveryTaskRepository,
                List.of(approvalHandler, cancelHandler),
                invalidLeaseDuration,
                clock,
                recoveryRetryPolicy,
                recoveryFailureClassifier
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Recovery worker lease duration must be positive");
    }

    @Test
    @DisplayName("claim 시각과 markResolved 시각을 Clock에서 각각 읽는다")
    void executeOne_resolved_shouldReadClaimAndCompletionTimeSeparately() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(resolvedResult());
        when(recoveryTaskRepository.markResolved(eq(TASK_ID), anyString(), eq(COMPLETED_AT))).thenReturn(1);

        worker.executeOne();

        ArgumentCaptor<String> claimTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(recoveryTaskRepository).claimNext(
                claimTokenCaptor.capture(),
                eq(CLAIMED_AT),
                eq(CLAIMED_AT.plus(LEASE_DURATION))
        );
        verify(recoveryTaskRepository).markResolved(
                TASK_ID,
                claimTokenCaptor.getValue(),
                COMPLETED_AT
        );
        verify(clock, org.mockito.Mockito.times(2)).instant();
    }

    private void assertRetryWaitTransition(int retryCount, LocalDateTime nextRetryAt) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, retryCount);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(recoveryRetryPolicy.canRetry(retryCount)).thenReturn(true);
        when(recoveryRetryPolicy.nextRetryAt(retryCount, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(recoveryTaskRepository.markRetryWait(
                eq(TASK_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt))).thenReturn(1);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
        assertThat(result.taskId()).isEqualTo(TASK_ID);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        ArgumentCaptor<String> claimTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(recoveryTaskRepository).claimNext(
                claimTokenCaptor.capture(),
                eq(CLAIMED_AT),
                eq(CLAIMED_AT.plus(LEASE_DURATION))
        );
        verify(recoveryRetryPolicy).canRetry(retryCount);
        verify(recoveryRetryPolicy).nextRetryAt(retryCount, COMPLETED_AT);
        verify(recoveryTaskRepository).markRetryWait(
                TASK_ID,
                claimTokenCaptor.getValue(),
                COMPLETED_AT,
                nextRetryAt
        );
        verify(recoveryTaskRepository, never()).markManualReview(any(), anyString(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    private void givenApprovalHandlerResult(RecoveryHandlerResult handlerResult) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
    }

    private void verifyNoRetryOrResolvedTransition() {
        verify(recoveryRetryPolicy, never()).canRetry(anyInt());
        verify(recoveryRetryPolicy, never()).nextRetryAt(anyInt(), any());
        verify(recoveryTaskRepository, never()).markRetryWait(any(), anyString(), any(), any());
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    private void verifyManualReviewUsesClaimTokenAndCompletionTime() {
        ArgumentCaptor<String> claimTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(recoveryTaskRepository).claimNext(
                claimTokenCaptor.capture(),
                eq(CLAIMED_AT),
                eq(CLAIMED_AT.plus(LEASE_DURATION))
        );
        verify(recoveryTaskRepository).markManualReview(
                TASK_ID,
                claimTokenCaptor.getValue(),
                COMPLETED_AT
        );
    }

    private void givenClaimedTask(RecoveryTask task) {
        when(recoveryTaskRepository.claimNext(
                anyString(),
                eq(CLAIMED_AT),
                eq(CLAIMED_AT.plus(LEASE_DURATION))
        )).thenReturn(Optional.of(task));
    }

    private static RecoveryHandler handler(RecoveryTargetType targetType) {
        RecoveryHandler handler = mock(RecoveryHandler.class);
        when(handler.targetType()).thenReturn(targetType);
        return handler;
    }

    private static RecoveryHandlerResult resolvedResult() {
        return new RecoveryHandlerResult(RecoveryHandlerResultType.RESOLVED, "APPROVED", "APPROVED");
    }

    private static RecoveryHandlerResult stillUnresolvedResult() {
        return new RecoveryHandlerResult(RecoveryHandlerResultType.STILL_UNRESOLVED, "UNKNOWN", "PROCESSING");
    }

    private static RecoveryTask task(RecoveryTargetType targetType) {
        return task(targetType, 0);
    }

    private static RecoveryTask task(RecoveryTargetType targetType, int retryCount) {
        return new RecoveryTask(
                TASK_ID,
                targetType,
                "TARGET-TRX-001",
                targetType == RecoveryTargetType.APPROVAL ? 1 : null,
                "ORIGINAL-TRX-001",
                1,
                RecoveryStatus.RUNNING,
                retryCount,
                null,
                "claimed-token",
                CLAIMED_AT.plus(LEASE_DURATION),
                CLAIMED_AT.minusMinutes(1),
                CLAIMED_AT
        );
    }

    private static Stream<Duration> invalidLeaseDurations() {
        return Stream.of(Duration.ZERO, Duration.ofSeconds(-1));
    }
}
