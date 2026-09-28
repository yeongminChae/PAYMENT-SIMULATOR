package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.ClaimedRecoveryExecution;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryExecutionTransactionService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryHistoryResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResultType;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/payment_sim_recovery_fencing_it.log"
})
public class PostgresRecoveryLeaseFencingIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 18, 10, 0);
    private static final LocalDateTime A_LEASE_EXPIRES_AT = T0.plusMinutes(5);
    private static final LocalDateTime B_LEASE_EXPIRES_AT = T0.plusMinutes(10);
    private static final LocalDateTime RESOLVE_AT = T0.plusMinutes(6);
    private static final String TEST_PREFIX = "R6P13-FENCING-";
    private static final String TARGET_TRX_NO = TEST_PREFIX + "001";
    private static final LocalDateTime CREATED_AT =  LocalDateTime.of(2026, 9, 18, 9, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 18, 9, 30);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_fencing_it")
                    .withUsername("payment_sim_recovery_fencing_it")
                    .withPassword("payment_sim_recovery_fencing_it");

    @Autowired
    private RecoveryTaskRepository taskRepository;

    @Autowired
    private RecoveryExecutionTransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecoveryHistoryRepository historyRepository;

    @BeforeEach
    void setUp() {
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    @Test
    void staleWorker_cannotResolve_afterTaskIsReclaimed() {
        // given
        Long taskId = insertPendingTask();

        // 1. Worker A claim
        ClaimedRecoveryExecution workerA =
                transactionService.claimAndStart("worker-a", T0, A_LEASE_EXPIRES_AT).orElseThrow();

        // 2. A lease가 만료된 정확한 시점에 Worker B reclaim
        ClaimedRecoveryExecution workerB =
                transactionService.claimAndStart("worker-b", A_LEASE_EXPIRES_AT, B_LEASE_EXPIRES_AT).orElseThrow();

        assertThat(workerA.task().id()).isEqualTo(taskId);
        assertThat(workerB.task().id()).isEqualTo(taskId);

        assertThat(workerA.task().claimToken()).isEqualTo("worker-a");
        assertThat(workerB.task().claimToken()).isEqualTo("worker-b");

        // 3. 현재 owner B가 먼저 resolve
        RecoveryWorkerResultType workerBResult =
                transactionService.resolve(taskId, workerB.history().id(), "worker-b", RESOLVE_AT);

        assertThat(workerBResult).isEqualTo(RecoveryWorkerResultType.RESOLVED);

        // 4. 늦게 돌아온 A가 예전 token으로 resolve 시도
        RecoveryWorkerResultType workerAResult =
                transactionService.resolve(taskId, workerA.history().id(), "worker-a", RESOLVE_AT);

        assertThat(workerAResult).isEqualTo(RecoveryWorkerResultType.OWNERSHIP_LOST);

        // 5. B가 만든 RESOLVED가 그대로 유지
        RecoveryTask task = taskRepository.findById(taskId).orElseThrow();

        List<RecoveryHistory> histories = historyRepository.findByRecoveryTaskId(taskId);

        assertThat(histories).hasSize(2);
        assertThat(histories.get(0).tryNo()).isEqualTo(1);
        assertThat(histories.get(0).result()).isEqualTo(RecoveryHistoryResult.OWNERSHIP_LOST.name());
        assertThat(histories.get(1).tryNo()).isEqualTo(2);
        assertThat(histories.get(1).result()).isEqualTo(RecoveryHistoryResult.RESOLVED.name());
        assertThat(task.recoveryStatus()).isEqualTo(RecoveryStatus.RESOLVED);
        assertThat(task.claimToken()).isNull();
        assertThat(task.leaseExpiresAt()).isNull();
    }

    private Long insertPendingTask() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO PAYMENT_RECOVERY_TASK (
                    TARGET_TYPE,
                    TARGET_TRX_NO,
                    TARGET_ATTEMPT_SEQ,
                    ORIGINAL_POS_TRX,
                    ORIGINAL_ATTEMPT_SEQ,
                    RECOVERY_STATUS,
                    RETRY_COUNT,
                    NEXT_RETRY_AT,
                    CLAIM_TOKEN,
                    LEASE_EXPIRES_AT,
                    CREATED_AT,
                    UPDATED_AT
                )
                VALUES (
                    'APPROVAL',
                    ?,
                    1,
                    ?,
                    1,
                    'PENDING',
                    0,
                    NULL,
                    NULL,
                    NULL,
                    ?,
                    ?
                )
                RETURNING ID
                """,
                Long.class,
                TARGET_TRX_NO,
                TARGET_TRX_NO + "-ORIGINAL",
                CREATED_AT,
                UPDATED_AT
        );
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                """
                DELETE FROM PAYMENT_RECOVERY_HISTORY
                WHERE RECOVERY_TASK_ID IN (
                    SELECT ID
                    FROM PAYMENT_RECOVERY_TASK
                    WHERE TARGET_TRX_NO LIKE ?
                )
                """,
                TEST_PREFIX + "%"
        );

        jdbcTemplate.update(
                "DELETE FROM PAYMENT_RECOVERY_TASK WHERE TARGET_TRX_NO LIKE ?",
                TEST_PREFIX + "%"
        );
    }

}
