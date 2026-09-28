package com.chaeyeongmin.payment_sim.api.payment.service.recovery.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RecoveryJobSchedulerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("payment.recovery.schedule.enabled=true")
            .withBean(JobLauncher.class, () -> mock(JobLauncher.class))
            .withBean("recoveryJob", Job.class, () -> mock(Job.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(RecoveryJobSchedulerTestConfig.class);

    @Test
    void default_profile에서_schedule_enabled_true여도_scheduler가_등록되지_않는다() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(RecoveryJobScheduler.class));
    }

    @Test
    void postgres_profile에서_schedule_enabled_true이면_scheduler가_등록된다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("postgres"))
                .run(context -> assertThat(context)
                        .hasSingleBean(RecoveryJobScheduler.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RecoveryJobScheduler.class)
    static class RecoveryJobSchedulerTestConfig {
    }
}
