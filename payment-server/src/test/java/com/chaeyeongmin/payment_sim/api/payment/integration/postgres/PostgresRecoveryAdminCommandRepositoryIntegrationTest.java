package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
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
        "logging.file.name=./build/logs/postgres-recovery-admin-command-repository-it.log"
})
public class PostgresRecoveryAdminCommandRepositoryIntegrationTest {
    private static final String TEST_PREFIX = "R6P12-2-ADMIN-";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 18, 8, 0);
    private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.of(2026, 9, 18, 9, 0);
    private static final LocalDateTime NEW_UPDATED_AT = LocalDateTime.of(2026, 9, 18, 10, 0);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_admin_command_it")
                    .withUsername("payment_sim_recovery_admin_command_it")
                    .withPassword("payment_sim_recovery_admin_command_it");

    @Autowired
    private RecoveryTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {cleanupTestData();}

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    @Test
    void requeueManualReview_resetsTaskForRecovery() {
        // given
        // MANUAL_REVIEW 상태 task를 DB에 INSERT
        // retryCount=3
        // nextRetryAt/claimToken/leaseExpiresAt 값도 일부러 채워둠
        Long taskId = insertTask("001", RecoveryStatus.MANUAL_REVIEW);

        // when
        int updated = repository.requeueManualReview(taskId, NEW_UPDATED_AT);

        // then
        assertThat(updated).isEqualTo(1);

        RecoveryTask task = repository.findById(taskId).orElseThrow();

        assertThat(task.recoveryStatus()).isEqualTo(RecoveryStatus.PENDING);
        assertThat(task.retryCount()).isZero();
        assertThat(task.nextRetryAt()).isNull();
        assertThat(task.claimToken()).isNull();
        assertThat(task.leaseExpiresAt()).isNull();
        assertThat(task.updatedAt()).isEqualTo(NEW_UPDATED_AT);
    }

    @Test
    void requeueManualReview_doesNothing_whenStatusIsNotManualReview() {
        // given: PENDING task
        Long taskId = insertTask("002", RecoveryStatus.PENDING);
        // when
        int updated = repository.requeueManualReview(taskId, NEW_UPDATED_AT);

        // 상태 그대로인지 확인
        // then
        assertThat(updated).isZero();

        RecoveryTask task = repository.findById(taskId).orElseThrow();

        assertThat(task.recoveryStatus()).isEqualTo(RecoveryStatus.PENDING);
        // 기존 값이 덮어써지지 않았는지도 확인
        assertThat(task.retryCount()).isEqualTo(3);
        assertThat(task.updatedAt()).isEqualTo(OLD_UPDATED_AT);

    }

    private Long insertTask(String suffix, RecoveryStatus status) {
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
                    ?,
                    3,
                    NULL,
                    NULL,
                    NULL,
                    ?,
                    ?
                )
                RETURNING ID
                """,
                Long.class,
                targetTrxNo,
                targetTrxNo,
                status.name(),
                CREATED_AT,
                OLD_UPDATED_AT
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
