package com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin;

import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskSummaryResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskNotFoundException;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskRequeueConflictException;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RecoveryAdminCommandServiceTest {

    private static final Long TASK_ID = 41L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 18, 9, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 18, 10, 0);

    @Mock
    private RecoveryTaskRepository repository;
    private Clock clock;
    private RecoveryAdminCommandService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(
                Instant.parse("2026-09-18T00:00:00Z"),
                ZoneOffset.UTC
        );

        service = new RecoveryAdminCommandService(
                repository,
                clock
        );
    }

    /*
    1. updated == 1
       + findById 있음
       → DTO 반환
     */
    @Test
    void requeue_returnsResponse_whenUpdateSucceeds() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);

        RecoveryTask recoveryTask = requeuedTask();

        when(repository.requeueManualReview(TASK_ID, now)).thenReturn(1);

        when(repository.findById(TASK_ID)).thenReturn(Optional.of(recoveryTask));

        // when
        RecoveryTaskSummaryResponse response = service.requeue(TASK_ID);

        // then
        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.recoveryStatus()).isEqualTo(RecoveryStatus.PENDING);
        assertThat(response.retryCount()).isZero();
    }

    /*
    2. updated == 0
       + findById 없음
       → RecoveryTaskNotFoundException
     */
    @Test
    void requeue_throwsNotFound_whenTaskDoesNotExist() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);

        when(repository.requeueManualReview(TASK_ID, now)).thenReturn(0);

        when(repository.findById(TASK_ID)).thenReturn(Optional.empty());

        // when
        assertThatThrownBy(() -> service.requeue(TASK_ID))
                .isInstanceOf(RecoveryTaskNotFoundException.class)
                .hasMessageContaining(TASK_ID.toString());
    }

    /*
     3. updated == 0
       + findById 있음
       → RecoveryTaskRequeueConflictException
     */
    @Test
    void requeue_throwsConflict_whenTaskExistsButCannotBeRequeued() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);

        RecoveryTask recoveryTask = requeuedTask();

        when(repository.requeueManualReview(TASK_ID, now)).thenReturn(0);

        when(repository.findById(TASK_ID)).thenReturn(Optional.of(recoveryTask));

        // when
        assertThatThrownBy(() -> service.requeue(TASK_ID))
                .isInstanceOf(RecoveryTaskRequeueConflictException.class)
                .hasMessageContaining(TASK_ID.toString());

        verify(repository).requeueManualReview(TASK_ID, now);
        verify(repository).findById(TASK_ID);
    }

    private RecoveryTask requeuedTask() {
        return new RecoveryTask(
                TASK_ID,
                RecoveryTargetType.APPROVAL,
                "ADMIN-APPROVAL-1",
                2,
                "ADMIN-APPROVAL-1",
                2,
                RecoveryStatus.PENDING,
                0,
                null,
                null,
                null,
                CREATED_AT,
                UPDATED_AT
        );
    }

}