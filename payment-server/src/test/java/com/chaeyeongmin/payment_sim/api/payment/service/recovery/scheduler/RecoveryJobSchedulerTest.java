package com.chaeyeongmin.payment_sim.api.payment.service.recovery.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecoveryJobSchedulerTest {

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job recoveryJob;

    @Mock
    private JobExecution jobExecution;

    private Clock clock;

    private RecoveryJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(
                Instant.parse("2026-09-17T06:00:00Z"),
                ZoneOffset.UTC
        );

        scheduler = new RecoveryJobScheduler(
                jobLauncher,
                recoveryJob,
                clock
        );
    }

    @Test
    void runRecoveryJob_launchesRecoveryJobWithScheduledAt() throws Exception {
        // given
        when(jobLauncher.run(eq(recoveryJob), any(JobParameters.class))).thenReturn(jobExecution);

        when(jobExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);

        // when
        scheduler.runRecoveryJob();

        // then
        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);

        verify(jobLauncher, times(1)).run(eq(recoveryJob), captor.capture());

        JobParameters params = captor.getValue();

        // 여기서 scheduledAt 검증
        Long scheduledAt = params.getLong("scheduledAt");
        assertThat(scheduledAt).isNotNull();
        assertThat(scheduledAt).isEqualTo(clock.millis());

    }

}