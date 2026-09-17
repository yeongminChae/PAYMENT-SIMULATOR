package com.chaeyeongmin.payment_sim.api.payment.service.recovery.batch;

import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "spring.batch.jdbc.initialize-schema=always",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key"
})

class RecoveryBatchJobSmokeTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_batch_it")
                    .withUsername("payment_sim_batch_it")
                    .withPassword("payment_sim_batch_it");

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("recoveryJob")
    private Job recoveryJob;

    @Test
    void recoveryJob_completes() throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addLong("run.id", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(recoveryJob, params);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        var stepExecutions = execution.getStepExecutions();

        // step이 2개인지 확인
        assertThat(stepExecutions).isNotNull().hasSize(2);

        // discoverAndRegisterRecoveryTasksStep 찾기
        // status == COMPLETED 확인
        var discoverStep = execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals("discoverAndRegisterRecoveryTasksStep"))
                .findFirst();

        assertThat(discoverStep).isPresent();
        var discoveredStep = discoverStep.get();
        assertThat(discoveredStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // processRecoveryTasksStep 찾기
        // status == COMPLETED 확인
        var processStep = execution.getStepExecutions().stream()
                .filter(step -> step.getStepName().equals("processRecoveryTasksStep"))
                .findFirst();

        assertThat(processStep).isPresent();
        var processedStep = processStep.get();
        assertThat(processedStep.getStatus()).isEqualTo(BatchStatus.COMPLETED);

    }

}
