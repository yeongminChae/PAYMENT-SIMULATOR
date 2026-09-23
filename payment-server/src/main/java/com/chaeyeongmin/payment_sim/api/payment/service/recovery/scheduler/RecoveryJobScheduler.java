package com.chaeyeongmin.payment_sim.api.payment.service.recovery.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 설정된 시간마다 Recovery Job을 실행한다.
 *
 * <p>{@code payment.recovery.schedule.enabled=true}일 때만 이 Bean이 만들어진다.
 * 기본값은 {@code false}이므로 운영자가 명시적으로 켜기 전에는 복구 배치를 실행하지 않는다.
 */
@Component
@ConditionalOnProperty(
        prefix = "payment.recovery.schedule",
        name = "enabled",
        havingValue = "true"
)
@Slf4j
public class RecoveryJobScheduler {

    /** Spring Batch Job을 실제로 시작하는 실행기다. */
    private final JobLauncher jobLauncher;

    /** 후보 등록과 Recovery Task 처리를 차례로 실행하는 Job이다. */
    private final Job recoveryJob;

    /** 실행 시각을 테스트에서도 일정하게 만들기 위해 주입받는 시간 기준이다. */
    private final Clock clock;

    /** 실행기, Recovery Job, 시간 기준을 주입받아 스케줄러를 구성한다. */
    public RecoveryJobScheduler(
            JobLauncher jobLauncher,
            @Qualifier("recoveryJob") Job recoveryJob,
            Clock clock
    ) {
        this.jobLauncher = jobLauncher;
        this.recoveryJob = recoveryJob;
        this.clock = clock;
    }

    /**
     * 이전 호출이 끝난 뒤 설정된 시간만큼 기다렸다가 Recovery Job을 다시 실행한다.
     *
     * <p>{@code scheduledAt}을 Job Parameter로 넣어 매 실행을 서로 다른 Job 실행으로 구분한다.
     * Job 실행 결과가 FAILED이거나 Job 시작 자체가 실패하면 운영자가 확인할 수 있도록 로그를 남긴다.
     */
    @Scheduled(fixedDelayString = "${payment.recovery.schedule.fixed-delay-ms:60000}")
    public void runRecoveryJob() {
        // 같은 파라미터의 완료된 Job은 다시 실행할 수 없으므로 현재 시각으로 실행마다 고유한 값을 만든다.
        JobParameters params = new JobParametersBuilder()
                .addLong("scheduledAt", clock.millis())
                .toJobParameters();

        try {
            JobExecution execution = jobLauncher.run(recoveryJob, params);

            // Job이 정상적으로 시작됐더라도 내부 Step 실패로 최종 상태가 FAILED일 수 있다.
            if (execution.getStatus() == BatchStatus.FAILED) {
                log.error(
                        "Recovery batch job failed. executionId={}, parameters={}",
                        execution.getId(),
                        params
                );
            }
        } catch (Exception e) {
            // 실행 요청 단계에서 발생한 예외를 기록하고 다음 스케줄 실행은 계속할 수 있게 한다.
            log.error(
                    "Recovery batch job launch failed. parameters={}",
                    params,
                    e
                );
        }
    }
}
