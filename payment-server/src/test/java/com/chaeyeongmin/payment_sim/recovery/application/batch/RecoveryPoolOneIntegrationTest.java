package com.chaeyeongmin.payment_sim.recovery.application.batch;

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
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.batch.job.enabled=false",
        "payment.recovery.schedule.enabled=false"
})
@ActiveProfiles("postgres")
class RecoveryPoolOneIntegrationTest {

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

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.zaxxer.hikari.HikariDataSource pool;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository attempts;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.chaeyeongmin.payment_sim.van.gateway.VanGateway van;

    @Test
    void poolOne_releasesBeforeInquiry_andFinalizesSyntheticAttempts() throws Exception {
        org.mockito.Mockito.when(van.inquiry(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            var request=(com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest)invocation.getArgument(0);
            boolean found=!request.targetTrxNo().endsWith("12") && !request.targetTrxNo().endsWith("13");
            return com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse.builder()
                    .targetType(request.targetType()).targetTrxNo(request.targetTrxNo()).targetAttemptSeq(1)
                    .resultCode(found?com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode.SUCCESS:com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode.NOT_FOUND)
                    .status(found?com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus.APPROVED:null)
                    .approvalNo(found?"TEST-"+request.targetTrxNo():null).vanTrxId(found?"VAN-"+request.targetTrxNo():null).build();
        });
        for(int i=1;i<=13;i++) jdbc.update("INSERT INTO payment_attempt(pos_trx,attempt_seq,amount,card_last4,created_at,updated_at) VALUES (?,1,10000,'1234',localtimestamp-interval '5 minutes',localtimestamp-interval '5 minutes')",String.format("SYNTHETIC-%02d",i));
        // Negative control: the former direct Mapper read retains a connection in the empty outer scope.
        var outer=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        outer.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outer.execute(status -> {
            attempts.findByPosTrxAndAttemptSeq("SYNTHETIC-01",1);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            return null;
        });
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        var execution=jobLauncher.run(recoveryJob,new JobParametersBuilder().addLong("pool1.test",System.nanoTime()).toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE final_status='APPROVED'",Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_attempt WHERE final_status IS NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_recovery_task WHERE recovery_status='RESOLVED' AND claim_token IS NULL AND lease_expires_at IS NULL",Integer.class)).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_recovery_task WHERE recovery_status='RETRY_WAIT' AND retry_count=1 AND claim_token IS NULL AND lease_expires_at IS NULL",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_recovery_history WHERE finished_at IS NOT NULL",Integer.class)).isEqualTo(13);
        org.mockito.Mockito.verify(van,org.mockito.Mockito.times(13)).inquiry(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoMoreInteractions(van);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_recovery_task t JOIN payment_recovery_history h ON h.recovery_task_id=t.id WHERE t.recovery_status='RETRY_WAIT' AND t.next_retry_at=h.finished_at+interval '1 minute'",Integer.class)).isEqualTo(2);
        // Replay synthetic resolved entries without waiting for the two backoffs.
        assertThat(jobLauncher.run(recoveryJob,new JobParametersBuilder().addLong("pool1.test",System.nanoTime()).toJobParameters()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_recovery_history",Integer.class)).isEqualTo(13);
        org.mockito.Mockito.verifyNoMoreInteractions(van);
    }
}
