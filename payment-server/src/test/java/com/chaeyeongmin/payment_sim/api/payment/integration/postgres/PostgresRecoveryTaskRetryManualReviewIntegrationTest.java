package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
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
        "logging.file.name=./build/logs/postgres-recovery-task-retry-manual-review-it.log"
})
class PostgresRecoveryTaskRetryManualReviewIntegrationTest {

    private static final String TEST_PREFIX = "R6P9-RETRY-MANUAL-";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 14, 0);
    private static final LocalDateTime ACTIVE_LEASE = NOW.plusMinutes(5);
    private static final LocalDateTime EXPIRED_LEASE = NOW.minusMinutes(1);
    private static final LocalDateTime NEXT_RETRY_AT = NOW.plusMinutes(10);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_task_retry_manual_it")
                    .withUsername("payment_sim_recovery_task_retry_manual_it")
                    .withPassword("payment_sim_recovery_task_retry_manual_it");

    @Autowired
    private RecoveryTaskRepository repository;

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
    @DisplayName("markRetryWait는 유효한 RUNNING owner를 RETRY_WAIT로 바꾸고 retry count를 누적한다")
    void markRetryWait_validOwner_shouldTransitionAndIncrementExistingRetryCount() {
        Long taskId = insertTask(
                "RETRY-SUCCESS",
                RecoveryStatus.RUNNING,
                2,
                null,
                "AAA",
                ACTIVE_LEASE
        );

        int updated = repository.markRetryWait(taskId, "AAA", NOW, NEXT_RETRY_AT);

        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = taskRow(taskId);
        assertThat(row.get("recovery_status")).isEqualTo("RETRY_WAIT");
        assertThat(row.get("retry_count")).isEqualTo(3);
        assertThat(asLocalDateTime(row.get("next_retry_at"))).isEqualTo(NEXT_RETRY_AT);
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("lease_expires_at")).isNull();
    }

    @Test
    @DisplayName("markRetryWait는 다른 claim token이면 row를 변경하지 않는다")
    void markRetryWait_wrongClaimToken_shouldNotChangeRow() {
        Long taskId = insertRunningTask("RETRY-WRONG-TOKEN", 2, "AAA", ACTIVE_LEASE);
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markRetryWait(taskId, "BBB", NOW, NEXT_RETRY_AT);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markRetryWait는 lease가 이미 만료됐으면 row를 변경하지 않는다")
    void markRetryWait_expiredLease_shouldNotChangeRow() {
        Long taskId = insertRunningTask("RETRY-EXPIRED", 2, "AAA", EXPIRED_LEASE);
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markRetryWait(taskId, "AAA", NOW, NEXT_RETRY_AT);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markRetryWait는 lease expiry가 now와 정확히 같으면 row를 변경하지 않는다")
    void markRetryWait_exactExpiry_shouldNotChangeRow() {
        Long taskId = insertRunningTask("RETRY-EXACT-EXPIRY", 2, "AAA", NOW);
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markRetryWait(taskId, "AAA", NOW, NEXT_RETRY_AT);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markRetryWait는 RUNNING이 아닌 task를 변경하지 않는다")
    void markRetryWait_nonRunningStatus_shouldNotChangeRow() {
        Long taskId = insertTask(
                "RETRY-NON-RUNNING",
                RecoveryStatus.RETRY_WAIT,
                2,
                NOW.plusMinutes(1),
                null,
                null
        );
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markRetryWait(taskId, "AAA", NOW, NEXT_RETRY_AT);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markRetryWait는 존재하지 않는 task를 변경하지 않는다")
    void markRetryWait_nonexistentTask_shouldReturnZero() {
        int updated = repository.markRetryWait(Long.MAX_VALUE, "AAA", NOW, NEXT_RETRY_AT);

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("markManualReview는 유효한 RUNNING owner를 MANUAL_REVIEW로 바꾸고 retry count를 보존한다")
    void markManualReview_validOwner_shouldTransitionAndPreserveRetryCount() {
        Long taskId = insertRunningTask("MANUAL-SUCCESS", 3, "AAA", ACTIVE_LEASE);

        int updated = repository.markManualReview(taskId, "AAA", NOW);

        assertThat(updated).isEqualTo(1);
        Map<String, Object> row = taskRow(taskId);
        assertThat(row.get("recovery_status")).isEqualTo("MANUAL_REVIEW");
        assertThat(row.get("retry_count")).isEqualTo(3);
        assertThat(row.get("next_retry_at")).isNull();
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("lease_expires_at")).isNull();
    }

    @Test
    @DisplayName("markManualReview는 다른 claim token이면 row를 변경하지 않는다")
    void markManualReview_wrongClaimToken_shouldNotChangeRow() {
        Long taskId = insertRunningTask("MANUAL-WRONG-TOKEN", 3, "AAA", ACTIVE_LEASE);
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markManualReview(taskId, "BBB", NOW);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markManualReview는 lease가 이미 만료됐으면 row를 변경하지 않는다")
    void markManualReview_expiredLease_shouldNotChangeRow() {
        Long taskId = insertRunningTask("MANUAL-EXPIRED", 3, "AAA", EXPIRED_LEASE);
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markManualReview(taskId, "AAA", NOW);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markManualReview는 lease expiry가 now와 정확히 같으면 row를 변경하지 않는다")
    void markManualReview_exactExpiry_shouldNotChangeRow() {
        Long taskId = insertRunningTask("MANUAL-EXACT-EXPIRY", 3, "AAA", NOW);
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markManualReview(taskId, "AAA", NOW);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markManualReview는 RUNNING이 아닌 task를 변경하지 않는다")
    void markManualReview_nonRunningStatus_shouldNotChangeRow() {
        Long taskId = insertTask(
                "MANUAL-NON-RUNNING",
                RecoveryStatus.RETRY_WAIT,
                3,
                NOW.plusMinutes(1),
                null,
                null
        );
        Map<String, Object> before = taskRow(taskId);

        int updated = repository.markManualReview(taskId, "AAA", NOW);

        assertThat(updated).isZero();
        assertRowUnchanged(taskId, before);
    }

    @Test
    @DisplayName("markManualReview는 존재하지 않는 task를 변경하지 않는다")
    void markManualReview_nonexistentTask_shouldReturnZero() {
        int updated = repository.markManualReview(Long.MAX_VALUE, "AAA", NOW);

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("reclaim 이후 stale owner는 RETRY_WAIT로 바꿀 수 없고 현재 owner만 바꿀 수 있다")
    void markRetryWait_afterReclaim_shouldFenceStaleOwner() {
        Long taskId = insertRunningTask("RETRY-RECLAIM", 2, "AAA", NOW);

        RecoveryTask reclaimed = repository.claimNext("BBB", NOW, ACTIVE_LEASE).orElseThrow();
        int staleOwnerUpdated = repository.markRetryWait(taskId, "AAA", NOW, NEXT_RETRY_AT);
        int currentOwnerUpdated = repository.markRetryWait(taskId, "BBB", NOW, NEXT_RETRY_AT);

        assertThat(reclaimed.id()).isEqualTo(taskId);
        assertThat(reclaimed.claimToken()).isEqualTo("BBB");
        assertThat(staleOwnerUpdated).isZero();
        assertThat(currentOwnerUpdated).isEqualTo(1);
        Map<String, Object> row = taskRow(taskId);
        assertThat(row.get("recovery_status")).isEqualTo("RETRY_WAIT");
        assertThat(row.get("retry_count")).isEqualTo(3);
        assertThat(asLocalDateTime(row.get("next_retry_at"))).isEqualTo(NEXT_RETRY_AT);
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("lease_expires_at")).isNull();
    }

    private Long insertRunningTask(
            String suffix,
            int retryCount,
            String claimToken,
            LocalDateTime leaseExpiresAt
    ) {
        return insertTask(
                suffix,
                RecoveryStatus.RUNNING,
                retryCount,
                null,
                claimToken,
                leaseExpiresAt
        );
    }

    private Long insertTask(
            String suffix,
            RecoveryStatus recoveryStatus,
            int retryCount,
            LocalDateTime nextRetryAt,
            String claimToken,
            LocalDateTime leaseExpiresAt
    ) {
        String targetTrxNo = TEST_PREFIX + suffix;
        jdbcTemplate.update(
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
                    LEASE_EXPIRES_AT
                )
                VALUES ('APPROVAL', ?, 1, ?, 1, ?, ?, ?, ?, ?)
                """,
                targetTrxNo,
                targetTrxNo + "-ORIGINAL",
                recoveryStatus.name(),
                retryCount,
                nextRetryAt,
                claimToken,
                leaseExpiresAt
        );
        return jdbcTemplate.queryForObject(
                "SELECT ID FROM PAYMENT_RECOVERY_TASK WHERE TARGET_TRX_NO = ?",
                Long.class,
                targetTrxNo
        );
    }

    private Map<String, Object> taskRow(Long taskId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT
                    RECOVERY_STATUS AS recovery_status,
                    RETRY_COUNT AS retry_count,
                    NEXT_RETRY_AT AS next_retry_at,
                    CLAIM_TOKEN AS claim_token,
                    LEASE_EXPIRES_AT AS lease_expires_at
                FROM PAYMENT_RECOVERY_TASK
                WHERE ID = ?
                """,
                taskId
        );
    }

    private void assertRowUnchanged(Long taskId, Map<String, Object> before) {
        assertThat(taskRow(taskId)).isEqualTo(before);
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
                "DELETE FROM PAYMENT_RECOVERY_TASK WHERE TARGET_TRX_NO LIKE ?",
                TEST_PREFIX + "%"
        );
    }
}
