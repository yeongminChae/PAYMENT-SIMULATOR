package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryTaskService;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
        "logging.file.name=./build/logs/postgres-recovery-task-registration-it.log"
})
class PostgresRecoveryTaskRegistrationIntegrationTest {

    private static final String TEST_PREFIX = "R6P4-";
    private static final LocalDateTime CANDIDATE_SINCE =
            LocalDateTime.of(2026, 9, 7, 8, 0);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_task_it")
                    .withUsername("payment_sim_recovery_task_it")
                    .withPassword("payment_sim_recovery_task_it");

    @Autowired
    private RecoveryTaskService recoveryTaskService;

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
    @DisplayName("Phase 4 recovery task 등록은 DB unique constraint로 중복 생성을 방지한다")
    void registersRecoveryTasksByDatabaseUniqueness() {
        RecoveryCandidate firstApproval = approval("R6P4-APPROVAL", 1);
        RecoveryCandidate secondApprovalAttempt = approval("R6P4-APPROVAL", 2);
        RecoveryCandidate cancel = cancel("R6P4-CANCEL", "R6P4-CANCEL-ORIGINAL", 1);
        RecoveryCandidate reversal = reversal("R6P4-REVERSAL", "R6P4-REVERSAL-ORIGINAL", 1);
        RecoveryCandidate sharedCancel = cancel("R6P4-SHARED", "R6P4-SHARED-CANCEL-ORIGINAL", 1);
        RecoveryCandidate sharedReversal = reversal("R6P4-SHARED", "R6P4-SHARED-REVERSAL-ORIGINAL", 1);

        assertThat(recoveryTaskService.registerIfAbsent(firstApproval)).isEqualTo(1);
        assertThat(taskCount(RecoveryTargetType.APPROVAL, "R6P4-APPROVAL")).isEqualTo(1);

        assertThat(recoveryTaskService.registerIfAbsent(firstApproval)).isEqualTo(0);
        assertThat(taskCount(RecoveryTargetType.APPROVAL, "R6P4-APPROVAL")).isEqualTo(1);

        assertThat(recoveryTaskService.registerIfAbsent(secondApprovalAttempt)).isEqualTo(1);
        assertThat(taskCount(RecoveryTargetType.APPROVAL, "R6P4-APPROVAL")).isEqualTo(2);

        assertThat(recoveryTaskService.registerIfAbsent(cancel)).isEqualTo(1);
        assertThat(recoveryTaskService.registerIfAbsent(cancel)).isEqualTo(0);
        assertThat(taskCount(RecoveryTargetType.CANCEL, "R6P4-CANCEL")).isEqualTo(1);

        assertThat(recoveryTaskService.registerIfAbsent(reversal)).isEqualTo(1);
        assertThat(recoveryTaskService.registerIfAbsent(reversal)).isEqualTo(0);
        assertThat(taskCount(RecoveryTargetType.REVERSAL, "R6P4-REVERSAL")).isEqualTo(1);

        assertThat(recoveryTaskService.registerIfAbsent(sharedCancel)).isEqualTo(1);
        assertThat(recoveryTaskService.registerIfAbsent(sharedReversal)).isEqualTo(1);
        assertThat(taskCount("R6P4-SHARED")).isEqualTo(2);

        assertThat(allTaskRows()).hasSize(6);
        assertThat(allTaskRows()).allSatisfy(row -> {
            assertThat(row.get("recovery_status")).isEqualTo("PENDING");
            assertThat(row.get("retry_count")).isEqualTo(0);
            assertThat(row.get("next_retry_at")).isNull();
            assertThat(row.get("claim_token")).isNull();
            assertThat(row.get("lease_expires_at")).isNull();
        });
    }

    private RecoveryCandidate approval(String posTrx, int attemptSeq) {
        return new RecoveryCandidate(
                RecoveryTargetType.APPROVAL,
                posTrx,
                attemptSeq,
                posTrx,
                attemptSeq,
                CANDIDATE_SINCE
        );
    }

    private RecoveryCandidate cancel(String currentTrxNo, String originalPosTrx, int originalAttemptSeq) {
        return new RecoveryCandidate(
                RecoveryTargetType.CANCEL,
                currentTrxNo,
                null,
                originalPosTrx,
                originalAttemptSeq,
                CANDIDATE_SINCE
        );
    }

    private RecoveryCandidate reversal(String currentTrxNo, String originalPosTrx, int originalAttemptSeq) {
        return new RecoveryCandidate(
                RecoveryTargetType.REVERSAL,
                currentTrxNo,
                null,
                originalPosTrx,
                originalAttemptSeq,
                CANDIDATE_SINCE
        );
    }

    private int taskCount(RecoveryTargetType targetType, String targetTrxNo) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM PAYMENT_RECOVERY_TASK
                WHERE TARGET_TYPE = ?
                  AND TARGET_TRX_NO = ?
                """,
                Integer.class,
                targetType.name(),
                targetTrxNo
        );
        return count == null ? 0 : count;
    }

    private int taskCount(String targetTrxNo) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM PAYMENT_RECOVERY_TASK
                WHERE TARGET_TRX_NO = ?
                """,
                Integer.class,
                targetTrxNo
        );
        return count == null ? 0 : count;
    }

    private List<Map<String, Object>> allTaskRows() {
        return jdbcTemplate.queryForList(
                """
                SELECT
                    RECOVERY_STATUS,
                    RETRY_COUNT,
                    NEXT_RETRY_AT,
                    CLAIM_TOKEN,
                    LEASE_EXPIRES_AT
                FROM PAYMENT_RECOVERY_TASK
                WHERE TARGET_TRX_NO LIKE ?
                   OR ORIGINAL_POS_TRX LIKE ?
                """,
                TEST_PREFIX + "%",
                TEST_PREFIX + "%"
        );
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                """
                DELETE FROM PAYMENT_RECOVERY_TASK
                WHERE TARGET_TRX_NO LIKE ?
                   OR ORIGINAL_POS_TRX LIKE ?
                """,
                TEST_PREFIX + "%",
                TEST_PREFIX + "%"
        );
    }
}
