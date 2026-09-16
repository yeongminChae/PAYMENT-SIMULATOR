package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.ClaimedRecoveryExecution;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryExecutionTransactionService;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/postgres-recovery-execution-transaction-service-it.log"
})
class PostgresRecoveryExecutionTransactionServiceIntegrationTest {

    private static final String TEST_PREFIX = "R6P9-5-EXECUTION-";
    private static final LocalDateTime FIRST_STARTED_AT = LocalDateTime.of(2026, 9, 16, 12, 0);
    private static final LocalDateTime FIRST_LEASE_EXPIRES_AT = FIRST_STARTED_AT.plusMinutes(5);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_execution_transaction_it")
                    .withUsername("payment_sim_recovery_execution_transaction_it")
                    .withPassword("payment_sim_recovery_execution_transaction_it");

    @Autowired
    private RecoveryExecutionTransactionService service;

    @Autowired
    private RecoveryTaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dropFailingHistoryTrigger();
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        dropFailingHistoryTrigger();
        cleanupTestData();
    }

    @Test
    void claimAndStart는_taskClaim과_tryNo1_history를함께commit한다() {
        Long taskId = insertPendingTask("SUCCESS");

        ClaimedRecoveryExecution execution = service.claimAndStart(
                "worker-first",
                FIRST_STARTED_AT,
                FIRST_LEASE_EXPIRES_AT
        ).orElseThrow();

        assertThat(execution.task().id()).isEqualTo(taskId);
        assertThat(execution.task().recoveryStatus()).isEqualTo(RecoveryStatus.RUNNING);
        assertThat(execution.task().claimToken()).isEqualTo("worker-first");
        assertThat(execution.task().leaseExpiresAt()).isEqualTo(FIRST_LEASE_EXPIRES_AT);
        assertThat(execution.history().recoveryTaskId()).isEqualTo(taskId);
        assertThat(execution.history().tryNo()).isEqualTo(1);
        assertThat(execution.history().startedAt()).isEqualTo(FIRST_STARTED_AT);

        Map<String, Object> taskRow = taskRow(taskId);
        assertThat(taskRow.get("recovery_status")).isEqualTo("RUNNING");
        assertThat(taskRow.get("claim_token")).isEqualTo("worker-first");
        assertThat(asLocalDateTime(taskRow.get("lease_expires_at")))
                .isEqualTo(FIRST_LEASE_EXPIRES_AT);
        assertThat(historyTryNos(taskId)).containsExactly(1);
    }

    @Test
    void retryWait이후다시claimAndStart하면_history를덮어쓰지않고_tryNo가증가한다() {
        Long taskId = insertPendingTask("RETRY");
        ClaimedRecoveryExecution first = service.claimAndStart(
                "worker-first",
                FIRST_STARTED_AT,
                FIRST_LEASE_EXPIRES_AT
        ).orElseThrow();
        LocalDateTime transitionAt = FIRST_STARTED_AT.plusMinutes(1);
        LocalDateTime retryAt = FIRST_STARTED_AT.plusMinutes(2);
        assertThat(taskRepository.markRetryWait(taskId, "worker-first", transitionAt, retryAt)).isEqualTo(1);

        ClaimedRecoveryExecution second = service.claimAndStart(
                "worker-second",
                retryAt,
                retryAt.plusMinutes(5)
        ).orElseThrow();

        assertThat(first.history().tryNo()).isEqualTo(1);
        assertThat(second.history().tryNo()).isEqualTo(2);
        assertThat(second.task().claimToken()).isEqualTo("worker-second");
        assertThat(second.task().retryCount()).isEqualTo(1);
        assertThat(historyTryNos(taskId)).containsExactly(1, 2);
    }

    @Test
    void historyInsert가실패하면_claim도rollback되어_task가PENDING으로남는다() {
        Long taskId = insertPendingTask("ROLLBACK");
        createFailingHistoryTrigger();

        assertThatThrownBy(() -> service.claimAndStart(
                "worker-rollback",
                FIRST_STARTED_AT,
                FIRST_LEASE_EXPIRES_AT
        )).rootCause().hasMessageContaining("forced recovery history insert failure");

        Map<String, Object> taskRow = taskRow(taskId);
        assertThat(taskRow.get("recovery_status")).isEqualTo("PENDING");
        assertThat(taskRow.get("claim_token")).isNull();
        assertThat(taskRow.get("lease_expires_at")).isNull();
        assertThat(historyTryNos(taskId)).isEmpty();
    }

    private Long insertPendingTask(String suffix) {
        String targetTrxNo = TEST_PREFIX + suffix;
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO PAYMENT_RECOVERY_TASK (
                    TARGET_TYPE,
                    TARGET_TRX_NO,
                    TARGET_ATTEMPT_SEQ,
                    ORIGINAL_POS_TRX,
                    ORIGINAL_ATTEMPT_SEQ,
                    RECOVERY_STATUS
                )
                VALUES ('APPROVAL', ?, 1, ?, 1, 'PENDING')
                RETURNING ID
                """,
                Long.class,
                targetTrxNo,
                targetTrxNo + "-ORIGINAL"
        );
    }

    private Map<String, Object> taskRow(Long taskId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT RECOVERY_STATUS, CLAIM_TOKEN, LEASE_EXPIRES_AT
                FROM PAYMENT_RECOVERY_TASK
                WHERE ID = ?
                """,
                taskId
        );
    }

    private List<Integer> historyTryNos(Long taskId) {
        return jdbcTemplate.queryForList(
                """
                SELECT TRY_NO
                FROM PAYMENT_RECOVERY_HISTORY
                WHERE RECOVERY_TASK_ID = ?
                ORDER BY TRY_NO
                """,
                Integer.class,
                taskId
        );
    }

    private void createFailingHistoryTrigger() {
        jdbcTemplate.execute(
                """
                CREATE OR REPLACE FUNCTION fail_recovery_history_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced recovery history insert failure';
                END;
                $$
                """
        );
        jdbcTemplate.execute(
                """
                CREATE TRIGGER fail_recovery_history_insert_trigger
                BEFORE INSERT ON PAYMENT_RECOVERY_HISTORY
                FOR EACH ROW
                EXECUTE FUNCTION fail_recovery_history_insert()
                """
        );
    }

    private void dropFailingHistoryTrigger() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS fail_recovery_history_insert_trigger ON PAYMENT_RECOVERY_HISTORY"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_recovery_history_insert() CASCADE");
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalArgumentException("Unsupported timestamp value: " + value);
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                """
                DELETE FROM PAYMENT_RECOVERY_HISTORY
                WHERE RECOVERY_TASK_ID IN (
                    SELECT ID FROM PAYMENT_RECOVERY_TASK WHERE TARGET_TRX_NO LIKE ?
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
