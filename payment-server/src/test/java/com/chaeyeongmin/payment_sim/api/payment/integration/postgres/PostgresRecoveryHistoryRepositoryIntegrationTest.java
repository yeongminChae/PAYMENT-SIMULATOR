package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryHistoryResult;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryHistoryRepository;
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
import java.util.Map;

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
        "logging.file.name=./build/logs/postgres-recovery-history-repository-it.log"
})
class PostgresRecoveryHistoryRepositoryIntegrationTest {

    private static final String TEST_PREFIX = "R6P9-5-HISTORY-";
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 16, 11, 0);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_history_repository_it")
                    .withUsername("payment_sim_recovery_history_repository_it")
                    .withPassword("payment_sim_recovery_history_repository_it");

    @Autowired
    private RecoveryHistoryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    @Test
    void history가없는task의_nextTryNo는1이다() {
        Long taskId = insertPendingTask("NO-HISTORY");

        assertThat(repository.nextTryNo(taskId)).isEqualTo(1);
    }

    @Test
    void tryNo1과2가있으면_nextTryNo는3이다() {
        Long taskId = insertPendingTask("TWO-HISTORIES");
        insertHistory(taskId, 1, STARTED_AT.minusMinutes(2));
        insertHistory(taskId, 2, STARTED_AT.minusMinutes(1));

        assertThat(repository.nextTryNo(taskId)).isEqualTo(3);
    }

    @Test
    void insertStarted는_started필드와생성ID를반환하고_finish필드는null로저장한다() {
        Long taskId = insertPendingTask("INSERT-STARTED");

        RecoveryHistory history = repository.insertStarted(taskId, 1, STARTED_AT);

        assertThat(history.id()).isNotNull();
        assertThat(history.recoveryTaskId()).isEqualTo(taskId);
        assertThat(history.tryNo()).isEqualTo(1);
        assertThat(history.result()).isNull();
        assertThat(history.errorCode()).isNull();
        assertThat(history.startedAt()).isEqualTo(STARTED_AT);
        assertThat(history.finishedAt()).isNull();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                """
                SELECT RECOVERY_TASK_ID, TRY_NO, RESULT, ERROR_CODE, STARTED_AT, FINISHED_AT
                FROM PAYMENT_RECOVERY_HISTORY
                WHERE ID = ?
                """,
                history.id()
        );
        assertThat(row.get("recovery_task_id")).isEqualTo(taskId);
        assertThat(row.get("try_no")).isEqualTo(1);
        assertThat(row.get("result")).isNull();
        assertThat(row.get("error_code")).isNull();
        assertThat(asLocalDateTime(row.get("started_at"))).isEqualTo(STARTED_AT);
        assertThat(row.get("finished_at")).isNull();
    }

    @Test
    void finish는_처음한번만성공하고_기존결과를덮어쓰지않는다() {
        Long taskId = insertPendingTask("DOUBLE-FINISH");
        RecoveryHistory history = repository.insertStarted(taskId, 1, STARTED_AT);
        LocalDateTime firstFinishedAt = STARTED_AT.plusMinutes(1);

        assertThat(repository.finish(
                history.id(),
                RecoveryHistoryResult.RETRY_WAIT,
                "VAN_GATEWAY_TIMEOUT",
                firstFinishedAt
        )).isEqualTo(1);
        assertThat(repository.finish(
                history.id(),
                RecoveryHistoryResult.MANUAL_REVIEW,
                "SHOULD_NOT_OVERWRITE",
                firstFinishedAt.plusMinutes(1)
        )).isZero();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT RESULT, ERROR_CODE, FINISHED_AT FROM PAYMENT_RECOVERY_HISTORY WHERE ID = ?",
                history.id()
        );
        assertThat(row.get("result")).isEqualTo("RETRY_WAIT");
        assertThat(row.get("error_code")).isEqualTo("VAN_GATEWAY_TIMEOUT");
        assertThat(asLocalDateTime(row.get("finished_at"))).isEqualTo(firstFinishedAt);
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

    private void insertHistory(Long taskId, int tryNo, LocalDateTime startedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_RECOVERY_HISTORY (RECOVERY_TASK_ID, TRY_NO, STARTED_AT)
                VALUES (?, ?, ?)
                """,
                taskId,
                tryNo,
                startedAt
        );
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
