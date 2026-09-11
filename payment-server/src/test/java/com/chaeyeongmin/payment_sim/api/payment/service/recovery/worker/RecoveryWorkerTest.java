package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandler;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResultType;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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

    @BeforeEach
    void setUp() {
        recoveryTaskRepository = mock(RecoveryTaskRepository.class);
        approvalHandler = handler(RecoveryTargetType.APPROVAL);
        cancelHandler = handler(RecoveryTargetType.CANCEL);
        clock = mock(Clock.class);

        when(clock.getZone()).thenReturn(ZONE_ID);
        when(clock.instant()).thenReturn(CLAIMED_INSTANT, COMPLETED_INSTANT);

        worker = new RecoveryWorker(
                recoveryTaskRepository,
                List.of(approvalHandler, cancelHandler),
                LEASE_DURATION,
                clock
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
    }

    @Test
    @DisplayName("APPROVAL task는 Approval Handler로 routing한다")
    void executeOne_approvalTask_shouldRouteToApprovalHandler() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        RecoveryHandlerResult handlerResult = stillUnresolvedResult();
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.STILL_UNRESOLVED);
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

        RecoveryWorkerResult result = worker.executeOne();

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.STILL_UNRESOLVED);
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
    @DisplayName("STILL_UNRESOLVED이면 markResolved를 호출하지 않는다")
    void executeOne_stillUnresolved_shouldNotMarkResolved() {
        RecoveryWorkerResult result = executeApprovalTaskWith(
                new RecoveryHandlerResult(RecoveryHandlerResultType.STILL_UNRESOLVED, "UNKNOWN", "PROCESSING")
        );

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.STILL_UNRESOLVED);
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("TERMINAL_CONFLICT이면 markResolved를 호출하지 않는다")
    void executeOne_terminalConflict_shouldNotMarkResolved() {
        RecoveryWorkerResult result = executeApprovalTaskWith(
                new RecoveryHandlerResult(RecoveryHandlerResultType.TERMINAL_CONFLICT, "APPROVED", "DECLINED")
        );

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.TERMINAL_CONFLICT);
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
    }

    @Test
    @DisplayName("TARGET_NOT_FOUND이면 markResolved를 호출하지 않는다")
    void executeOne_targetNotFound_shouldNotMarkResolved() {
        RecoveryWorkerResult result = executeApprovalTaskWith(
                new RecoveryHandlerResult(RecoveryHandlerResultType.TARGET_NOT_FOUND, null, null)
        );

        assertThat(result.resultType()).isEqualTo(RecoveryWorkerResultType.TARGET_NOT_FOUND);
        verify(recoveryTaskRepository, never()).markResolved(any(), anyString(), any());
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
                clock
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
                clock
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

    private RecoveryWorkerResult executeApprovalTaskWith(RecoveryHandlerResult handlerResult) {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL);
        givenClaimedTask(task);
        when(approvalHandler.handle(task)).thenReturn(handlerResult);
        return worker.executeOne();
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
        return new RecoveryTask(
                TASK_ID,
                targetType,
                "TARGET-TRX-001",
                targetType == RecoveryTargetType.APPROVAL ? 1 : null,
                "ORIGINAL-TRX-001",
                1,
                RecoveryStatus.RUNNING,
                0,
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
