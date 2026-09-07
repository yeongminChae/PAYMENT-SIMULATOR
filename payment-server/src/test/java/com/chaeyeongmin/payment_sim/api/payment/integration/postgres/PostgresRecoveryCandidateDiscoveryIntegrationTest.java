package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.RecoveryCandidateMapper;
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
        "logging.file.name=./build/logs/postgres-recovery-candidate-discovery-it.log"
})
class PostgresRecoveryCandidateDiscoveryIntegrationTest {

    private static final String TEST_PREFIX = "R6P3-";

    private static final LocalDateTime UNKNOWN_TIMEOUT_BEFORE =
            LocalDateTime.of(2026, 9, 7, 12, 0);
    private static final LocalDateTime STALE_PROCESSING_BEFORE =
            LocalDateTime.of(2026, 9, 7, 11, 0);
    private static final LocalDateTime STALE_PENDING_BEFORE =
            LocalDateTime.of(2026, 9, 7, 10, 0);

    private static final LocalDateTime OLD_UNKNOWN_UPDATED_AT =
            LocalDateTime.of(2026, 9, 7, 8, 10);
    private static final LocalDateTime OLD_PROCESSING_CREATED_AT =
            LocalDateTime.of(2026, 9, 7, 8, 20);
    private static final LocalDateTime OLD_PENDING_CREATED_AT =
            LocalDateTime.of(2026, 9, 7, 8, 30);
    private static final LocalDateTime RECENT_AT =
            LocalDateTime.of(2026, 9, 7, 13, 0);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_candidate_it")
                    .withUsername("payment_sim_recovery_candidate_it")
                    .withPassword("payment_sim_recovery_candidate_it");

    @Autowired
    private RecoveryCandidateMapper recoveryCandidateMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanupTestData();
        insertFixtures();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    @Test
    @DisplayName("Phase 3 candidate 조회는 오래된 미확정 거래만 identity와 기준 시각을 보존해 반환한다")
    void findsOnlyStaleAmbiguousCandidatesWithPreservedIdentityAndCandidateSince() {
        int recoveryTaskCountBefore = recoveryTaskCount();

        List<RecoveryCandidate> approvals = recoveryCandidateMapper.findApprovalCandidates(
                UNKNOWN_TIMEOUT_BEFORE,
                STALE_PROCESSING_BEFORE
        );
        List<RecoveryCandidate> cancels = recoveryCandidateMapper.findCancelCandidates(
                UNKNOWN_TIMEOUT_BEFORE,
                STALE_PENDING_BEFORE
        );
        List<RecoveryCandidate> reversals = recoveryCandidateMapper.findReversalCandidates(
                STALE_PENDING_BEFORE
        );

        assertThat(approvals).containsExactlyInAnyOrder(
                new RecoveryCandidate(
                        RecoveryTargetType.APPROVAL,
                        "R6P3-A-OLD-UNKNOWN",
                        2,
                        "R6P3-A-OLD-UNKNOWN",
                        2,
                        OLD_UNKNOWN_UPDATED_AT
                ),
                new RecoveryCandidate(
                        RecoveryTargetType.APPROVAL,
                        "R6P3-A-OLD-PROCESSING",
                        3,
                        "R6P3-A-OLD-PROCESSING",
                        3,
                        OLD_PROCESSING_CREATED_AT
                )
        );

        assertThat(cancels).containsExactlyInAnyOrder(
                new RecoveryCandidate(
                        RecoveryTargetType.CANCEL,
                        "R6P3-C-OLD-UNKNOWN",
                        null,
                        "R6P3-C-ORIGINAL-UNKNOWN",
                        4,
                        OLD_UNKNOWN_UPDATED_AT
                ),
                new RecoveryCandidate(
                        RecoveryTargetType.CANCEL,
                        "R6P3-C-OLD-PENDING",
                        null,
                        "R6P3-C-ORIGINAL-PENDING",
                        5,
                        OLD_PENDING_CREATED_AT
                )
        );

        assertThat(reversals).containsExactly(
                new RecoveryCandidate(
                        RecoveryTargetType.REVERSAL,
                        "R6P3-R-OLD-PENDING",
                        null,
                        "R6P3-R-ORIGINAL-PENDING",
                        6,
                        OLD_PENDING_CREATED_AT
                )
        );

        assertThat(recoveryTaskCount()).isEqualTo(recoveryTaskCountBefore);
    }

    private void insertFixtures() {
        insertAttempt("R6P3-A-OLD-UNKNOWN", 2, "UNKNOWN_TIMEOUT",
                LocalDateTime.of(2026, 9, 7, 7, 0), OLD_UNKNOWN_UPDATED_AT);
        insertAttempt("R6P3-A-OLD-PROCESSING", 3, null,
                OLD_PROCESSING_CREATED_AT, RECENT_AT);
        insertAttempt("R6P3-A-RECENT-UNKNOWN", 1, "UNKNOWN_TIMEOUT",
                LocalDateTime.of(2026, 9, 7, 7, 0), RECENT_AT);
        insertAttempt("R6P3-A-RECENT-PROCESSING", 1, null,
                RECENT_AT, RECENT_AT);
        insertAttempt("R6P3-A-TERMINAL", 1, "APPROVED",
                LocalDateTime.of(2026, 9, 7, 7, 0), LocalDateTime.of(2026, 9, 7, 7, 10));

        insertOriginalAttempt("R6P3-C-ORIGINAL-UNKNOWN", 4);
        insertOriginalAttempt("R6P3-C-ORIGINAL-PENDING", 5);
        insertOriginalAttempt("R6P3-C-ORIGINAL-RECENT-UNKNOWN", 1);
        insertOriginalAttempt("R6P3-C-ORIGINAL-RECENT-PENDING", 1);
        insertOriginalAttempt("R6P3-C-ORIGINAL-TERMINAL", 1);

        insertCancel("R6P3-C-OLD-UNKNOWN", "R6P3-C-ORIGINAL-UNKNOWN", 4,
                "UNKNOWN_TIMEOUT", LocalDateTime.of(2026, 9, 7, 7, 0), OLD_UNKNOWN_UPDATED_AT);
        insertCancel("R6P3-C-OLD-PENDING", "R6P3-C-ORIGINAL-PENDING", 5,
                "PENDING", OLD_PENDING_CREATED_AT, RECENT_AT);
        insertCancel("R6P3-C-RECENT-UNKNOWN", "R6P3-C-ORIGINAL-RECENT-UNKNOWN", 1,
                "UNKNOWN_TIMEOUT", LocalDateTime.of(2026, 9, 7, 7, 0), RECENT_AT);
        insertCancel("R6P3-C-RECENT-PENDING", "R6P3-C-ORIGINAL-RECENT-PENDING", 1,
                "PENDING", RECENT_AT, RECENT_AT);
        insertCancel("R6P3-C-TERMINAL", "R6P3-C-ORIGINAL-TERMINAL", 1,
                "CANCELLED", LocalDateTime.of(2026, 9, 7, 7, 0), LocalDateTime.of(2026, 9, 7, 7, 10));

        insertOriginalAttempt("R6P3-R-ORIGINAL-PENDING", 6);
        insertOriginalAttempt("R6P3-R-ORIGINAL-RECENT", 1);
        insertOriginalAttempt("R6P3-R-ORIGINAL-TERMINAL", 1);

        insertReversal("R6P3-R-OLD-PENDING", "R6P3-R-ORIGINAL-PENDING", 6,
                "PENDING", OLD_PENDING_CREATED_AT);
        insertReversal("R6P3-R-RECENT-PENDING", "R6P3-R-ORIGINAL-RECENT", 1,
                "PENDING", RECENT_AT);
        insertReversal("R6P3-R-TERMINAL", "R6P3-R-ORIGINAL-TERMINAL", 1,
                "REVERSED", LocalDateTime.of(2026, 9, 7, 7, 0));
    }

    private void insertOriginalAttempt(String posTrx, int attemptSeq) {
        insertAttempt(
                posTrx,
                attemptSeq,
                "APPROVED",
                LocalDateTime.of(2026, 9, 7, 6, 0),
                LocalDateTime.of(2026, 9, 7, 6, 10)
        );
    }

    private void insertAttempt(
            String posTrx,
            int attemptSeq,
            String finalStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_ATTEMPT (
                    POS_TRX, ATTEMPT_SEQ, AMOUNT, FINAL_STATUS, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                posTrx, attemptSeq, 10000, finalStatus, createdAt, updatedAt
        );
    }

    private void insertCancel(
            String currentTrxNo,
            String originalTrxNo,
            int originalAttemptSeq,
            String cancelStatus,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_CANCEL (
                    CURRENT_TRX_NO, ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ,
                    CANCEL_STATUS, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                currentTrxNo, originalTrxNo, originalAttemptSeq,
                cancelStatus, createdAt, updatedAt
        );
    }

    private void insertReversal(
            String currentTrxNo,
            String originalTrxNo,
            int originalAttemptSeq,
            String reversalStatus,
            LocalDateTime createdAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_REVERSAL (
                    CURRENT_TRX_NO, ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ,
                    AMOUNT, REVERSAL_STATUS, CREATED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                currentTrxNo, originalTrxNo, originalAttemptSeq,
                10000, reversalStatus, createdAt, createdAt
        );
    }

    private int recoveryTaskCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PAYMENT_RECOVERY_TASK",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM PAYMENT_REVERSAL WHERE CURRENT_TRX_NO LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO LIKE ?", TEST_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX LIKE ?", TEST_PREFIX + "%");
    }
}
