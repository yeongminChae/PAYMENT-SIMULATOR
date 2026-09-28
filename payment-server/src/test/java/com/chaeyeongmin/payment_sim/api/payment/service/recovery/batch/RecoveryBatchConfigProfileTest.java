package com.chaeyeongmin.payment_sim.api.payment.service.recovery.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RecoveryBatchConfigProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JobRepository.class, () -> mock(JobRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(RecoveryDiscoveryTasklet.class, () -> mock(RecoveryDiscoveryTasklet.class))
            .withBean(RecoveryProcessTasklet.class, () -> mock(RecoveryProcessTasklet.class))
            .withUserConfiguration(RecoveryBatchConfigTestConfig.class);

    @Test
    void default_profile에서는_recoveryJob이_등록되지_않는다() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean("recoveryJob"));
    }

    @Test
    void postgres_profile에서는_recoveryJob이_등록된다() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("postgres"))
                .run(context -> assertThat(context)
                        .hasBean("recoveryJob")
                        .hasSingleBean(Job.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RecoveryBatchConfig.class)
    static class RecoveryBatchConfigTestConfig {
    }
}
