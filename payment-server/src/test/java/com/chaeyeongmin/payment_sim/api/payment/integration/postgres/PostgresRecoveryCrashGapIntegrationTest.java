package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorker;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResult;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker.RecoveryWorkerResultType;
import com.chaeyeongmin.payment_sim.van.client.dto.*;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Import(PostgresRecoveryCrashGapIntegrationTest.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/postgres-recovery-crash-gap-it.log"
})
class PostgresRecoveryCrashGapIntegrationTest {

    private static final String POS_TRX = "R6P13-CRASH-GAP-001";
    private static final int ATTEMPT_SEQ = 1;

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 18, 15, 0);

    private static final LocalDateTime OLD_LEASE_EXPIRES_AT =
            NOW.minusMinutes(1);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_crash_gap_it")
                    .withUsername("payment_sim_recovery_crash_gap_it")
                    .withPassword("payment_sim_recovery_crash_gap_it");

    @Autowired
    private RecoveryWorker recoveryWorker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CountingVanGateway vanGateway;

    @BeforeEach
    void setUp() {
        cleanupTestData();
        vanGateway.reset();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
        vanGateway.reset();
    }

    @Test
    void expiredRunningTask_reclaimsAndResolvesWhenTargetAlreadyFinalized() {

        /*
         * 이전 Worker가 target ledger는 APPROVED로 확정했지만,
         * Recovery Task를 RESOLVED로 바꾸기 전에 죽은 상태를 만든다.
         */
        insertApprovedAttempt();

        Long taskId = insertExpiredRunningTask();

        // 실제 crash 당시 Worker가 이미 claimAndStart까지 했던 것을 재현한다.
        insertOldStartedHistory(taskId);

        /*
         * 새 Worker 실행.
         *
         * expired RUNNING task를 reclaim한 뒤
         * ApprovalRecoveryHandler가 PAYMENT_ATTEMPT를 다시 읽는다.
         *
         * 이미 APPROVED terminal이므로 VAN Inquiry 없이
         * RESOLVED shortcut이 발생해야 한다.
         */
        RecoveryWorkerResult result = recoveryWorker.executeOne();

        assertThat(result.resultType())
                .isEqualTo(RecoveryWorkerResultType.RESOLVED);

        assertThat(result.taskId())
                .isEqualTo(taskId);

        // 거래 원장은 APPROVED 그대로 보존
        assertThat(storedAttemptStatus())
                .isEqualTo("APPROVED");

        // Recovery Task도 최종 완료
        Map<String, Object> task = taskRow(taskId);

        assertThat(task.get("recovery_status"))
                .isEqualTo("RESOLVED");

        assertThat(task.get("claim_token"))
                .isNull();

        assertThat(task.get("lease_expires_at"))
                .isNull();

        // 이미 terminal이므로 VAN을 다시 건드리면 안 됨
        assertThat(vanGateway.approveCount()).isZero();
        assertThat(vanGateway.inquiryCount()).isZero();

        // reclaim된 새 Worker 실행은 두 번째 History가 된다.
        assertThat(historyTryNos(taskId))
                .containsExactly(1, 2);

        assertThat(historyResult(taskId, 2))
                .isEqualTo("RESOLVED");
    }

    private void insertApprovedAttempt() {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_ATTEMPT (
                    POS_TRX,
                    ATTEMPT_SEQ,
                    AMOUNT,
                    CARD_BIN,
                    CARD_LAST4,
                    CARD_BRAND,
                    CARD_FINGERPRINT,
                    FINAL_STATUS,
                    APPROVAL_NO,
                    VAN_TRX_ID
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                POS_TRX,
                ATTEMPT_SEQ,
                10000,
                "424242",
                "4242",
                "VISA",
                "fingerprint-" + POS_TRX,
                "APPROVED",
                "APP-R6P13-001",
                "VAN-R6P13-001"
        );
    }

    private Long insertExpiredRunningTask() {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(
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
                            ?,
                            ?,
                            ?,
                            'RUNNING',
                            0,
                            NULL,
                            'crashed-worker',
                            ?,
                            ?,
                            ?
                        )
                        RETURNING ID
                        """,
                        Long.class,
                        POS_TRX,
                        ATTEMPT_SEQ,
                        POS_TRX,
                        ATTEMPT_SEQ,
                        OLD_LEASE_EXPIRES_AT,
                        NOW.minusMinutes(5),
                        NOW.minusMinutes(2)
                )
        );
    }

    private void insertOldStartedHistory(Long taskId) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_RECOVERY_HISTORY (
                    RECOVERY_TASK_ID,
                    TRY_NO,
                    RESULT,
                    ERROR_CODE,
                    STARTED_AT,
                    FINISHED_AT
                )
                VALUES (?, 1, NULL, NULL, ?, NULL)
                """,
                taskId,
                NOW.minusMinutes(2)
        );
    }

    private String storedAttemptStatus() {
        return jdbcTemplate.queryForObject(
                """
                SELECT FINAL_STATUS
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                String.class,
                POS_TRX,
                ATTEMPT_SEQ
        );
    }

    private Map<String, Object> taskRow(Long taskId) {
        return jdbcTemplate.queryForMap(
                """
                SELECT
                    RECOVERY_STATUS AS recovery_status,
                    CLAIM_TOKEN AS claim_token,
                    LEASE_EXPIRES_AT AS lease_expires_at
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

    private String historyResult(Long taskId, int tryNo) {
        return jdbcTemplate.queryForObject(
                """
                SELECT RESULT
                FROM PAYMENT_RECOVERY_HISTORY
                WHERE RECOVERY_TASK_ID = ?
                  AND TRY_NO = ?
                """,
                String.class,
                taskId,
                tryNo
        );
    }

    static class CountingVanGateway implements VanGateway {

        private final AtomicInteger approveCount = new AtomicInteger();
        private final AtomicInteger inquiryCount = new AtomicInteger();

        @Override
        public VanApproveResponse approve(VanApproveRequest request) {
            approveCount.incrementAndGet();
            throw new AssertionError(
                    "Crash-gap terminal shortcut must not call VAN approve"
            );
        }

        @Override
        public VanInquiryResponse inquiry(VanInquiryRequest request) {
            inquiryCount.incrementAndGet();
            throw new AssertionError(
                    "Already-finalized recovery target must not call VAN inquiry"
            );
        }

        @Override
        public VanCancelResponse cancel(VanCancelRequest request) {
            throw new AssertionError("cancel is not used");
        }

        @Override
        public VanReversalResponse reversal(VanReversalRequest request) {
            throw new AssertionError("reversal is not used");
        }

        int approveCount() {
            return approveCount.get();
        }

        int inquiryCount() {
            return inquiryCount.get();
        }

        void reset() {
            approveCount.set(0);
            inquiryCount.set(0);
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(
                    NOW.atZone(ZONE_ID).toInstant(),
                    ZONE_ID
            );
        }

        @Bean
        @Primary
        CountingVanGateway countingVanGateway() {
            return new CountingVanGateway();
        }
    }

    private void cleanupTestData() {

        jdbcTemplate.update(
                """
                DELETE FROM PAYMENT_RECOVERY_HISTORY
                WHERE RECOVERY_TASK_ID IN (
                    SELECT ID
                    FROM PAYMENT_RECOVERY_TASK
                    WHERE TARGET_TRX_NO = ?
                )
                """,
                POS_TRX
        );

        jdbcTemplate.update(
                "DELETE FROM PAYMENT_RECOVERY_TASK WHERE TARGET_TRX_NO = ?",
                POS_TRX
        );

        jdbcTemplate.update(
                "DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?",
                POS_TRX
        );
    }

}