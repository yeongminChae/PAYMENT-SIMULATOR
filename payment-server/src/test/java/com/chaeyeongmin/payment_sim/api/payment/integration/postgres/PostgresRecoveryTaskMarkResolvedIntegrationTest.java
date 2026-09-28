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
        "logging.file.name=./build/logs/postgres-recovery-task-mark-resolved-it.log"
})
class PostgresRecoveryTaskMarkResolvedIntegrationTest {

    private static final String TEST_PREFIX = "R6P7-MARK-RESOLVED-";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 10, 0);
    private static final LocalDateTime ACTIVE_LEASE = NOW.plusMinutes(5);
    private static final LocalDateTime EXPIRED_LEASE = NOW;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_task_mark_resolved_it")
                    .withUsername("payment_sim_recovery_task_mark_resolved_it")
                    .withPassword("payment_sim_recovery_task_mark_resolved_it");

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
    @DisplayName("RUNNING task는 같은 token과 유효한 lease로 RESOLVED 처리된다")
    void RUNNING_task는_같은_token과_유효한_lease로_RESOLVED_처리된다() {
        Long taskId = insertTask("VALID", RecoveryStatus.RUNNING, "AAA", ACTIVE_LEASE);

        int updated = repository.markResolved(taskId, "AAA", NOW);

        assertThat(updated).isEqualTo(1);
        assertThat(taskRow(taskId).get("recovery_status")).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("RUNNING task는 다른 token으로 RESOLVED 처리되지 않는다")
    void RUNNING_task는_다른_token으로_RESOLVED_처리되지_않는다() {
        Long taskId = insertTask("DIFFERENT-TOKEN", RecoveryStatus.RUNNING, "AAA", ACTIVE_LEASE);

        int updated = repository.markResolved(taskId, "BBB", NOW);

        assertThat(updated).isZero();
        assertThat(taskRow(taskId).get("recovery_status")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("RUNNING task는 같은 token이어도 lease가 만료되면 RESOLVED 처리되지 않는다")
    void RUNNING_task는_같은_token이어도_lease가_만료되면_RESOLVED_처리되지_않는다() {
        Long taskId = insertTask("EXPIRED", RecoveryStatus.RUNNING, "AAA", EXPIRED_LEASE);

        int updated = repository.markResolved(taskId, "AAA", NOW);

        assertThat(updated).isZero();
        assertThat(taskRow(taskId).get("recovery_status")).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("이미 RESOLVED인 task는 다시 RESOLVED 처리되지 않는다")
    void 이미_RESOLVED인_task는_다시_RESOLVED_처리되지_않는다() {
        Long taskId = insertTask("ALREADY-RESOLVED", RecoveryStatus.RESOLVED, null, null);

        int updated = repository.markResolved(taskId, "AAA", NOW);

        assertThat(updated).isZero();
    }

    @Test
    @DisplayName("reclaim된 task는 이전 token이 아니라 현재 token만 RESOLVED 처리할 수 있다")
    void reclaim된_task는_이전_token이_아니라_현재_token만_RESOLVED_처리할_수_있다() {
        Long taskId = insertTask("RECLAIM", RecoveryStatus.RUNNING, "AAA", EXPIRED_LEASE);

        RecoveryTask reclaimed = repository.claimNext("BBB", NOW, ACTIVE_LEASE).orElseThrow();
        int previousOwnerUpdated = repository.markResolved(taskId, "AAA", NOW);
        int currentOwnerUpdated = repository.markResolved(taskId, "BBB", NOW);

        assertThat(reclaimed.id()).isEqualTo(taskId);
        assertThat(reclaimed.claimToken()).isEqualTo("BBB");
        assertThat(previousOwnerUpdated).isZero();
        assertThat(currentOwnerUpdated).isEqualTo(1);
        assertThat(taskRow(taskId).get("recovery_status")).isEqualTo("RESOLVED");
    }

    private Long insertTask(
            String suffix,
            RecoveryStatus recoveryStatus,
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
                    CLAIM_TOKEN,
                    LEASE_EXPIRES_AT
                )
                VALUES ('APPROVAL', ?, 1, ?, 1, ?, ?, ?)
                """,
                targetTrxNo,
                targetTrxNo + "-ORIGINAL",
                recoveryStatus.name(),
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
                SELECT RECOVERY_STATUS AS recovery_status
                FROM PAYMENT_RECOVERY_TASK
                WHERE ID = ?
                """,
                taskId
        );
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_RECOVERY_TASK WHERE TARGET_TRX_NO LIKE ?",
                TEST_PREFIX + "%"
        );
    }
}
