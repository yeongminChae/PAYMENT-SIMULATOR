package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.exception.RecoveryInvariantViolationException;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure.RecoveryFailureClassifier;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.failure.RecoveryFailureType;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandler;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResultType;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.notification.RecoveryManualReviewNotification;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.notification.RecoveryNotificationService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.policy.RecoveryRetryPolicy;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.ClaimedRecoveryExecution;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryExecutionTransactionService;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryWorkerTest {

    private static final long TASK_ID = 1L;
    private static final long HISTORY_ID = 11L;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 9, 11, 10, 0);
    private static final LocalDateTime COMPLETED_AT = CLAIMED_AT.plusSeconds(31);
    private static final Instant CLAIMED_INSTANT = CLAIMED_AT.atZone(ZONE_ID).toInstant();
    private static final Instant COMPLETED_INSTANT = COMPLETED_AT.atZone(ZONE_ID).toInstant();

    private RecoveryExecutionTransactionService transactionService;
    private RecoveryHandler approvalHandler;
    private RecoveryHandler cancelHandler;
    private Clock clock;
    private RecoveryRetryPolicy retryPolicy;
    private RecoveryFailureClassifier failureClassifier;
    private RecoveryNotificationService notificationService;
    private RecoveryWorker worker;

    @BeforeEach
    void setUp() {
        transactionService = mock(RecoveryExecutionTransactionService.class);
        approvalHandler = handler(RecoveryTargetType.APPROVAL);
        cancelHandler = handler(RecoveryTargetType.CANCEL);
        clock = mock(Clock.class);
        retryPolicy = mock(RecoveryRetryPolicy.class);
        failureClassifier = mock(RecoveryFailureClassifier.class);
        notificationService = mock(RecoveryNotificationService.class);
        when(clock.getZone()).thenReturn(ZONE_ID);
        when(clock.instant()).thenReturn(CLAIMED_INSTANT, COMPLETED_INSTANT);
        worker = new RecoveryWorker(
                transactionService,
                List.of(approvalHandler, cancelHandler),
                LEASE_DURATION,
                clock,
                retryPolicy,
                failureClassifier,
                notificationService
        );
    }

    @Test
    void claim할Task가없으면_noTask이고_handler를호출하지않는다() {
        when(transactionService.claimAndStart(anyString(), eq(CLAIMED_AT), eq(CLAIMED_AT.plus(LEASE_DURATION))))
                .thenReturn(Optional.empty());

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.NO_TASK);
        assertThat(result.taskId()).isNull();
        assertThat(result.handlerResult()).isNull();
        verify(approvalHandler, never()).handle(any());
    }

    @Test
    void resolved는_transactionService로_task와history를종료하고_handlerResult를보존한다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RecoveryHandlerResult handlerResult = result(RecoveryHandlerResultType.RESOLVED);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(transactionService.resolve(eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(RecoveryWorkerResultType.RESOLVED);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RESOLVED);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verify(transactionService).resolve(eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT));
        verify(notificationService, never()).notifyManualReview(any());
    }

    @Test
    void resolved전이결과가_ownershipLost이면그대로반환한다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RecoveryHandlerResult handlerResult = result(RecoveryHandlerResultType.RESOLVED);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(transactionService.resolve(eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT)))
                .thenReturn(RecoveryWorkerResultType.OWNERSHIP_LOST);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void stillUnresolved이고재시도가능하면_errorCode없이_retryWait한다(int retryCount) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, retryCount);
        RecoveryHandlerResult handlerResult = result(RecoveryHandlerResultType.STILL_UNRESOLVED);
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(retryCount + 1L);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(retryPolicy.canRetry(retryCount)).thenReturn(true);
        when(retryPolicy.nextRetryAt(retryCount, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(transactionService.retryWait(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt), eq(null)))
                .thenReturn(RecoveryWorkerResultType.RETRY_WAIT);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verify(notificationService, never()).notifyManualReview(any());
    }

    @Test
    void stillUnresolved이고재시도소진이면_retryExhausted로_manualReview한다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 3);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(result(RecoveryHandlerResultType.STILL_UNRESOLVED));
        when(retryPolicy.canRetry(3)).thenReturn(false);
        when(transactionService.manualReview(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq("RETRY_EXHAUSTED")))
                .thenReturn(RecoveryWorkerResultType.MANUAL_REVIEW);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        verify(retryPolicy, never()).nextRetryAt(anyInt(), any());
    }

    @ParameterizedTest
    @MethodSource("manualReviewResults")
    void terminal결과는_안정적인errorCode로_manualReview한다(
            RecoveryHandlerResultType resultType,
            String errorCode
    ) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RecoveryHandlerResult handlerResult = result(resultType);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        when(transactionService.manualReview(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq(errorCode)))
                .thenReturn(RecoveryWorkerResultType.MANUAL_REVIEW);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isSameAs(handlerResult);
        verify(retryPolicy, never()).canRetry(anyInt());

        ArgumentCaptor<RecoveryManualReviewNotification> notificationCaptor =
                ArgumentCaptor.forClass(RecoveryManualReviewNotification.class);
        verify(notificationService).notifyManualReview(notificationCaptor.capture());

        RecoveryManualReviewNotification notification = notificationCaptor.getValue();
        assertThat(notification.taskId()).isEqualTo(TASK_ID);
        assertThat(notification.targetType()).isEqualTo(RecoveryTargetType.APPROVAL);
        assertThat(notification.targetTrxNo()).isEqualTo("TARGET-TRX-001");
        assertThat(notification.retryCount()).isZero();
        assertThat(notification.errorCode()).isEqualTo(errorCode);
        assertThat(notification.occurredAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void manualReview전이에서소유권을잃으면_notification을호출하지않는다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(result(RecoveryHandlerResultType.TERMINAL_CONFLICT));
        when(transactionService.manualReview(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq("TERMINAL_CONFLICT")))
                .thenReturn(RecoveryWorkerResultType.OWNERSHIP_LOST);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);
        verify(notificationService, never()).notifyManualReview(any());
    }

    @Test
    void notification실패는_manualReview결과를바꾸지않는다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(result(RecoveryHandlerResultType.TARGET_NOT_FOUND));
        when(transactionService.manualReview(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq("TARGET_NOT_FOUND")))
                .thenReturn(RecoveryWorkerResultType.MANUAL_REVIEW);
        doThrow(new RuntimeException("notification failed"))
                .when(notificationService).notifyManualReview(any());

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        verify(notificationService).notifyManualReview(any());
    }

    @ParameterizedTest
    @MethodSource("retryableExceptions")
    void retryable예외는_종류별errorCode로_retryWait한다(RuntimeException exception, String errorCode) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(1);
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(failureClassifier.classify(exception)).thenReturn(RecoveryFailureType.RETRYABLE);
        when(retryPolicy.canRetry(0)).thenReturn(true);
        when(retryPolicy.nextRetryAt(0, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(transactionService.retryWait(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt), eq(errorCode)))
                .thenReturn(RecoveryWorkerResultType.RETRY_WAIT);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.RETRY_WAIT);
        assertThat(result.handlerResult()).isNull();
    }

    @Test
    void retryable예외가재시도소진이면_예외errorCode를유지한다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 3);
        RuntimeException exception = new VanGatewayTimeoutException(new RuntimeException());
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(failureClassifier.classify(exception)).thenReturn(RecoveryFailureType.RETRYABLE);
        when(retryPolicy.canRetry(3)).thenReturn(false);
        when(transactionService.manualReview(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq("VAN_GATEWAY_TIMEOUT")))
                .thenReturn(RecoveryWorkerResultType.MANUAL_REVIEW);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isNull();
    }

    @Test
    void invariant예외는_message를errorCode로_manualReview한다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RuntimeException exception =
                new RecoveryInvariantViolationException("RECOVERY_APPROVAL_INQUIRY_RESPONSE_INVALID");
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(failureClassifier.classify(exception)).thenReturn(RecoveryFailureType.MANUAL_REVIEW);
        when(transactionService.manualReview(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT),
                eq("RECOVERY_APPROVAL_INQUIRY_RESPONSE_INVALID")))
                .thenReturn(RecoveryWorkerResultType.MANUAL_REVIEW);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.MANUAL_REVIEW);
        assertThat(result.handlerResult()).isNull();
    }

    @Test
    void unknown예외는_history를종료한뒤_원본instance를재전파한다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        NullPointerException exception = new NullPointerException("unknown");
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenThrow(exception);
        when(failureClassifier.classify(exception)).thenReturn(RecoveryFailureType.UNKNOWN);

        assertThatThrownBy(worker::executeOne).isSameAs(exception);

        verify(transactionService).unknownFailure(HISTORY_ID, "NullPointerException", COMPLETED_AT);
        verify(transactionService, never()).resolve(any(), any(), anyString(), any());
        verify(transactionService, never()).retryWait(any(), any(), anyString(), any(), any(), any());
        verify(transactionService, never()).manualReview(any(), any(), anyString(), any(), any());
    }

    @Test
    void unknownFailure저장이실패하면_DB예외가우선한다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        RuntimeException handlerException = new RuntimeException("handler failed");
        RuntimeException databaseException = new RuntimeException("history finish failed");
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenThrow(handlerException);
        when(failureClassifier.classify(handlerException)).thenReturn(RecoveryFailureType.UNKNOWN);
        org.mockito.Mockito.doThrow(databaseException)
                .when(transactionService).unknownFailure(HISTORY_ID, "RuntimeException", COMPLETED_AT);

        assertThatThrownBy(worker::executeOne).isSameAs(databaseException);
    }

    @Test
    void claimAndStart예외는_classifier로분류하지않는다() {
        RuntimeException exception = new RuntimeException("claim failed");
        when(transactionService.claimAndStart(anyString(), eq(CLAIMED_AT), eq(CLAIMED_AT.plus(LEASE_DURATION))))
                .thenThrow(exception);

        assertThatThrownBy(worker::executeOne).isSameAs(exception);
        verify(failureClassifier, never()).classify(any());
    }

    @ParameterizedTest
    @MethodSource("transitionFailures")
    void transitionService예외는_classifier로분류하지않는다(RecoveryHandlerResultType resultType) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL,
                resultType == RecoveryHandlerResultType.STILL_UNRESOLVED ? 3 : 0);
        RuntimeException exception = new RuntimeException("transition failed");
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(result(resultType));

        if (resultType == RecoveryHandlerResultType.RESOLVED) {
            when(transactionService.resolve(eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT)))
                    .thenThrow(exception);
        } else {
            when(retryPolicy.canRetry(3)).thenReturn(false);
            when(transactionService.manualReview(
                    eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), anyString()))
                    .thenThrow(exception);
        }

        assertThatThrownBy(worker::executeOne).isSameAs(exception);
        verify(failureClassifier, never()).classify(any());
    }

    @Test
    void retryWaitService예외는_classifier로분류하지않는다() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, 0);
        LocalDateTime nextRetryAt = COMPLETED_AT.plusMinutes(1);
        RuntimeException exception = new RuntimeException("retry transition failed");
        givenClaimed(task);
        when(approvalHandler.handle(task)).thenReturn(result(RecoveryHandlerResultType.STILL_UNRESOLVED));
        when(retryPolicy.canRetry(0)).thenReturn(true);
        when(retryPolicy.nextRetryAt(0, COMPLETED_AT)).thenReturn(nextRetryAt);
        when(transactionService.retryWait(
                eq(TASK_ID), eq(HISTORY_ID), anyString(), eq(COMPLETED_AT), eq(nextRetryAt), eq(null)))
                .thenThrow(exception);

        assertThatThrownBy(worker::executeOne).isSameAs(exception);
        verify(failureClassifier, never()).classify(any());
    }

    @Test
    void 같은targetType_handler가둘이면생성에실패한다() {
        assertThatThrownBy(() -> new RecoveryWorker(
                transactionService,
                List.of(approvalHandler, handler(RecoveryTargetType.APPROVAL)),
                LEASE_DURATION,
                clock,
                retryPolicy,
                failureClassifier,
                notificationService
        )).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidLeaseDurations")
    void leaseDuration이0이하면생성에실패한다(Duration duration) {
        assertThatThrownBy(() -> new RecoveryWorker(
                transactionService,
                List.of(approvalHandler),
                duration,
                clock,
                retryPolicy,
                failureClassifier,
                notificationService
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private void givenClaimed(RecoveryTask task) {
        RecoveryHistory history = new RecoveryHistory(HISTORY_ID, TASK_ID, 1, null, null, CLAIMED_AT, null);
        when(transactionService.claimAndStart(
                anyString(), eq(CLAIMED_AT), eq(CLAIMED_AT.plus(LEASE_DURATION))))
                .thenReturn(Optional.of(new ClaimedRecoveryExecution(task, history)));
    }

    private static RecoveryHandler handler(RecoveryTargetType targetType) {
        RecoveryHandler handler = mock(RecoveryHandler.class);
        when(handler.targetType()).thenReturn(targetType);
        return handler;
    }

    private static RecoveryHandlerResult result(RecoveryHandlerResultType type) {
        return new RecoveryHandlerResult(type, "UNKNOWN", "PROCESSING");
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

    private static Stream<Arguments> manualReviewResults() {
        return Stream.of(
                Arguments.of(RecoveryHandlerResultType.TERMINAL_CONFLICT, "TERMINAL_CONFLICT"),
                Arguments.of(RecoveryHandlerResultType.TARGET_NOT_FOUND, "TARGET_NOT_FOUND")
        );
    }

    private static Stream<Arguments> retryableExceptions() {
        return Stream.of(
                Arguments.of(new VanGatewayTimeoutException(new RuntimeException()), "VAN_GATEWAY_TIMEOUT"),
                Arguments.of(
                        new VanGatewayRequestNotSentException(new RuntimeException()),
                        "VAN_GATEWAY_REQUEST_NOT_SENT"
                )
        );
    }

    private static Stream<RecoveryHandlerResultType> transitionFailures() {
        return Stream.of(
                RecoveryHandlerResultType.RESOLVED,
                RecoveryHandlerResultType.STILL_UNRESOLVED
        );
    }

    private static Stream<Duration> invalidLeaseDurations() {
        return Stream.of(Duration.ZERO, Duration.ofSeconds(-1));
    }
}
