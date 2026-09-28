package com.chaeyeongmin.payment_sim.api.payment.service.recovery.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.SimpleJob;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RecoveryBatchConfigTest {

    @Test
    void recoveryJob과두step을순서대로구성한다() {
        RecoveryBatchConfig config = new RecoveryBatchConfig();
        JobRepository jobRepository = mock(JobRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        RecoveryDiscoveryTasklet discoveryTasklet = mock(RecoveryDiscoveryTasklet.class);
        RecoveryProcessTasklet processTasklet = mock(RecoveryProcessTasklet.class);

        Step discoveryStep = config.discoverAndRegisterRecoveryTasksStep(
                jobRepository,
                transactionManager,
                discoveryTasklet
        );
        Step processStep = config.processRecoveryTasksStep(
                jobRepository,
                transactionManager,
                processTasklet
        );
        Job job = config.recoveryJob(jobRepository, discoveryStep, processStep);

        assertThat(job.getName()).isEqualTo("recoveryJob");
        assertThat(discoveryStep.getName()).isEqualTo("discoverAndRegisterRecoveryTasksStep");
        assertThat(processStep.getName()).isEqualTo("processRecoveryTasksStep");
        assertThat(((SimpleJob) job).getStepNames()).containsExactly(
                "discoverAndRegisterRecoveryTasksStep",
                "processRecoveryTasksStep"
        );

        DefaultTransactionAttribute transactionAttribute = (DefaultTransactionAttribute)
                ReflectionTestUtils.getField((TaskletStep) processStep, "transactionAttribute");
        assertThat(transactionAttribute).isNotNull();
        assertThat(transactionAttribute.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
}
