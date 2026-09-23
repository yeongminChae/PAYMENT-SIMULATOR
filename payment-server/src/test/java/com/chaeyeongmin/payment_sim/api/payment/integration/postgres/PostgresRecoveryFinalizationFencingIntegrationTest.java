package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.ClaimedRecoveryExecution;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.transaction.RecoveryExecutionTransactionService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryTaskRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
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

import java.time.Clock;
import java.time.LocalDateTime;

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
        "logging.file.name=./build/logs/payment_sim_recovery_finalization_fencing_it.log"
})
class PostgresRecoveryFinalizationFencingIntegrationTest {

    private static final String TEST_PREFIX = "R6-P1-FINALIZE-FENCE-";
    private static final String POS_TRX = TEST_PREFIX + "001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_finalization_fencing_it")
                    .withUsername("payment_sim_finalization_fencing_it")
                    .withPassword("payment_sim_finalization_fencing_it");

    @Autowired
    private RecoveryFinalizationService finalizationService;

    @Autowired
    private RecoveryExecutionTransactionService transactionService;

    @Autowired
    private RecoveryTaskRepository recoveryTaskRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void staleWorkerCannotFinalizeApprovalAfterTaskIsReclaimed() {
        // given
        LocalDateTime now = LocalDateTime.now(clock);

        /*
         * Worker A가 Task를 claim한다.
         *
         * 여기서는 기다리거나 sleep할 필요 없이
         * A의 lease 만료 시각을 과거 시각으로 만들어도 된다.
         */
        LocalDateTime workerAClaimedAt = now.minusMinutes(2);
        LocalDateTime workerALeaseExpiresAt = now.minusMinutes(1);

        /*
         * PAYMENT_ATTEMPT 생성
         *
         * POS_TRX = POS_TRX
         * ATTEMPT_SEQ = 1
         * FINAL_STATUS = UNKNOWN_TIMEOUT
         */
        insertUnknownTimeoutAttempt();

        /*
         * APPROVAL Recovery Task를 PENDING으로 생성한다.
         */
        Long taskId = insertPendingApprovalRecoveryTask(now);

        ClaimedRecoveryExecution workerA =
                transactionService
                        .claimAndStart("worker-a", workerAClaimedAt, workerALeaseExpiresAt)
                        .orElseThrow();

        /*
         * A가 claim 당시 받은 RecoveryTask 객체.
         *
         * 이 객체에는 claimToken = worker-a가 들어있다.
         * 이후 B가 reclaim하더라도 이 Java 객체 자체는 worker-a 상태 그대로다.
         */
        RecoveryTask staleTaskFromWorkerA = workerA.task();

        /*
         * 현재 시각 기준으로 A lease는 이미 만료됐으므로
         * Worker B가 같은 Task를 reclaim한다.
         */
        ClaimedRecoveryExecution workerB =
                transactionService
                        .claimAndStart("worker-b", now, now.plusMinutes(5))
                        .orElseThrow();

        /*
         * 여기까지 DB 상태:
         *
         * Recovery Task:
         * RUNNING / claimToken = worker-b
         *
         * 하지만 A가 들고 있는 객체:
         * RUNNING / claimToken = worker-a
         */

        assertThat(workerA.task().id()).isEqualTo(taskId);
        assertThat(workerB.task().id()).isEqualTo(taskId);
        assertThat(staleTaskFromWorkerA.claimToken()).isEqualTo("worker-a");
        assertThat(workerB.task().claimToken()).isEqualTo("worker-b");

        /*
         * A가 과거에 VAN Inquiry에서 APPROVED를 받았다고 가정한다.
         */
        AttemptResultUpdateParam intended =
                AttemptResultUpdateParam.approved(
                        POS_TRX,
                        1,
                        "APPROVAL-P1-FENCE",
                        "VAN-P1-FENCE"
                );

        /*
         * when
         *
         * 이미 ownership을 잃은 Worker A가
         * stale RecoveryTask를 사용해 Ledger finalization을 시도한다.
         */
        RecoveryFinalizeResult result =
                finalizationService.finalizeApproval(staleTaskFromWorkerA, intended);

        // then 1
        // A는 현재 owner가 아니므로 Ledger를 건드릴 권한이 없다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.OWNERSHIP_LOST);

        /*
         * then 2
         *
         * 가장 중요한 검증.
         *
         * stale Worker A가 APPROVED를 들고 왔더라도
         * PAYMENT_ATTEMPT는 UNKNOWN_TIMEOUT 그대로여야 한다.
         */
        PaymentAttempt attempt =
                attemptRepository.findByPosTrxAndAttemptSeq(POS_TRX, 1).orElseThrow();
        assertThat(attempt.getFinalStatusEnum()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        /*
         * then 3
         *
         * Recovery Task ownership 역시 B에게 그대로 남아 있어야 한다.
         */
        RecoveryTask currentTask = recoveryTaskRepository.findById(taskId).orElseThrow();
        assertThat(currentTask.recoveryStatus()).isEqualTo(RecoveryStatus.RUNNING);
        assertThat(currentTask.claimToken()).isEqualTo("worker-b");
    }

    private void insertUnknownTimeoutAttempt() {
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
                    FINAL_STATUS
                )
                VALUES (?, 1, 10000, '424242', '4242', 'VISA', ?, 'UNKNOWN_TIMEOUT')
                """,
                POS_TRX,
                "fingerprint-" + POS_TRX
        );

    }

    private Long insertPendingApprovalRecoveryTask(LocalDateTime now) {
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
                VALUES ('APPROVAL', ?, 1, ?, 1, 'PENDING', 0, NULL, NULL, NULL, ?, ?)
                RETURNING ID
                """,
                Long.class,
                POS_TRX,
                POS_TRX,
                now,
                now
        );
    }

    private void cleanup() {
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

        jdbcTemplate.update(
                "DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX LIKE ?",
                TEST_PREFIX + "%"
        );
    }

}