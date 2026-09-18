package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryHistory;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
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
        "logging.file.name=./build/logs/postgres-recovery-admin-query-repository-it.log"
})
class PostgresRecoveryAdminQueryRepositoryIntegrationTest {

    private static final String TEST_PREFIX = "R6P12-1-ADMIN-";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 17, 8, 0);
    private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.of(2026, 9, 17, 9, 0);
    private static final LocalDateTime NEW_UPDATED_AT = LocalDateTime.of(2026, 9, 17, 10, 0);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_admin_query_it")
                    .withUsername("payment_sim_recovery_admin_query_it")
                    .withPassword("payment_sim_recovery_admin_query_it");

    @Autowired
    private RecoveryTaskRepository taskRepository;

    @Autowired
    private RecoveryHistoryRepository historyRepository;

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
    void task상태조회와상세조회는_PostgreSQL에서조건과정렬을보존한다() {
        Long oldTaskId = insertTask("OLD", "MANUAL_REVIEW", OLD_UPDATED_AT);
        Long firstNewTaskId = insertTask("NEW-1", "MANUAL_REVIEW", NEW_UPDATED_AT);
        Long secondNewTaskId = insertTask("NEW-2", "MANUAL_REVIEW", NEW_UPDATED_AT);
        insertTask("RESOLVED", "RESOLVED", NEW_UPDATED_AT.plusHours(1));

        List<RecoveryTask> tasks = taskRepository.findByStatus(RecoveryStatus.MANUAL_REVIEW);

        assertThat(tasks).extracting(RecoveryTask::id)
                .containsExactly(secondNewTaskId, firstNewTaskId, oldTaskId);
        assertThat(tasks).allSatisfy(task ->
                assertThat(task.recoveryStatus()).isEqualTo(RecoveryStatus.MANUAL_REVIEW));

        RecoveryTask detail = taskRepository.findById(firstNewTaskId).orElseThrow();
        assertThat(detail.id()).isEqualTo(firstNewTaskId);
        assertThat(detail.targetType()).isEqualTo(RecoveryTargetType.APPROVAL);
        assertThat(detail.targetTrxNo()).isEqualTo(TEST_PREFIX + "NEW-1");
        assertThat(detail.updatedAt()).isEqualTo(NEW_UPDATED_AT);
        assertThat(taskRepository.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void history조회는_tryNo오름차순으로전체필드를반환한다() {
        Long taskId = insertTask("HISTORY", "MANUAL_REVIEW", NEW_UPDATED_AT);
        insertHistory(taskId, 2, "MANUAL_REVIEW", "RETRY_EXHAUSTED", CREATED_AT.plusMinutes(2));
        insertHistory(taskId, 1, "RETRY_WAIT", "VAN_TIMEOUT", CREATED_AT.plusMinutes(1));

        List<RecoveryHistory> histories = historyRepository.findByRecoveryTaskId(taskId);

        assertThat(histories).extracting(RecoveryHistory::tryNo).containsExactly(1, 2);
        assertThat(histories.get(0).recoveryTaskId()).isEqualTo(taskId);
        assertThat(histories.get(0).result()).isEqualTo("RETRY_WAIT");
        assertThat(histories.get(0).errorCode()).isEqualTo("VAN_TIMEOUT");
        assertThat(histories.get(0).startedAt()).isEqualTo(CREATED_AT.plusMinutes(1));
        assertThat(histories.get(0).finishedAt()).isEqualTo(CREATED_AT.plusMinutes(1).plusSeconds(10));
    }

    private Long insertTask(String suffix, String status, LocalDateTime updatedAt) {
        String targetTrxNo = TEST_PREFIX + suffix;
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
                    CREATED_AT,
                    UPDATED_AT
                )
                VALUES ('APPROVAL', ?, 1, ?, 1, ?, 2, ?, ?)
                RETURNING ID
                """,
                Long.class,
                targetTrxNo,
                targetTrxNo,
                status,
                CREATED_AT,
                updatedAt
        );
    }

    private void insertHistory(
            Long taskId,
            int tryNo,
            String result,
            String errorCode,
            LocalDateTime startedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_RECOVERY_HISTORY (
                    RECOVERY_TASK_ID, TRY_NO, RESULT, ERROR_CODE, STARTED_AT, FINISHED_AT
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                taskId,
                tryNo,
                result,
                errorCode,
                startedAt,
                startedAt.plusSeconds(10)
        );
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
