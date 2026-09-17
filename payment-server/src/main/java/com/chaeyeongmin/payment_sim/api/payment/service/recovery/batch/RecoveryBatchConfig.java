package com.chaeyeongmin.payment_sim.api.payment.service.recovery.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/** Recovery 배치의 실행 순서와 각 Step의 트랜잭션 방식을 설정한다. */
@Configuration
public class RecoveryBatchConfig {

    /** 후보 등록 Step을 먼저 실행하고, 성공하면 Task 처리 Step을 실행하는 Job을 만든다. */
    @Bean
    public Job recoveryJob(
            JobRepository jobRepository,
            Step discoverAndRegisterRecoveryTasksStep,
            Step processRecoveryTasksStep
    ) {
        return new JobBuilder("recoveryJob", jobRepository)
                .start(discoverAndRegisterRecoveryTasksStep)
                .next(processRecoveryTasksStep)
                .build();
    }

    /** 복구가 필요한 거래를 찾고 Recovery Task로 등록하는 첫 번째 Step을 만든다. */
    @Bean
    public Step discoverAndRegisterRecoveryTasksStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RecoveryDiscoveryTasklet recoveryDiscoveryTasklet
    ) {
        return new StepBuilder("discoverAndRegisterRecoveryTasksStep", jobRepository)
                .tasklet(recoveryDiscoveryTasklet, transactionManager)
                .build();
    }

    /**
     * 등록된 Recovery Task를 더 이상 처리할 건이 없을 때까지 실행하는 두 번째 Step을 만든다.
     *
     * <p>Worker는 Task claim과 처리 결과 저장을 각각 짧은 트랜잭션으로 실행한다.
     * Batch가 Tasklet 전체를 하나의 트랜잭션으로 감싸면 VAN 통신 중에도 DB 트랜잭션이
     * 유지될 수 있으므로, 이 Step의 바깥 트랜잭션은 사용하지 않는다.
     */
    @Bean
    public Step processRecoveryTasksStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            RecoveryProcessTasklet recoveryProcessTasklet
    ) {
        DefaultTransactionAttribute noOuterTransaction = new DefaultTransactionAttribute();

        // Tasklet 바깥의 Batch 트랜잭션을 없애 Worker 내부 TX1/VAN/TX2 경계를 그대로 유지한다.
        noOuterTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        return new StepBuilder("processRecoveryTasksStep", jobRepository)
                .tasklet(recoveryProcessTasklet, transactionManager)
                .transactionAttribute(noOuterTransaction)
                .build();
    }

}
