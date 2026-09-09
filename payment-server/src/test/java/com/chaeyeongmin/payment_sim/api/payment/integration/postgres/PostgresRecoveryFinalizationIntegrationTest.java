package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
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
        "logging.file.name=./build/logs/postgres-recovery-finalization-it.log"
})
class PostgresRecoveryFinalizationIntegrationTest {

    private static final String TEST_PREFIX = "R6P6-";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_finalization_it")
                    .withUsername("payment_sim_recovery_finalization_it")
                    .withPassword("payment_sim_recovery_finalization_it");

    @Autowired
    private RecoveryFinalizationService service;

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
    @DisplayName("approval recovery는 PROCESSING row를 APPROVED로 확정하고 반복 호출은 idempotent하다")
    void approval_processing_to_approved_is_applied_and_idempotent() {
        // finalizeApproval(): 실제 PostgreSQL에서 FINAL_STATUS IS NULL row만 APPROVED로 확정되는지 검증한다.
        // 두 번째 호출은 conditional update miss 후 reread로 같은 terminal을 확인해 idempotent하게 수렴해야 한다.
        insertAttempt("R6P6-APP-IDEMPOTENT", 1, null);
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.approved(
                "R6P6-APP-IDEMPOTENT",
                1,
                "APPROVAL-R6P6",
                "VAN-APP-R6P6"
        );

        // first: PROCESSING(null) row가 recoverable 조건에 걸려 APPROVED로 실제 UPDATE 되어야 한다.
        RecoveryFinalizeResult first = service.finalizeApproval(intended);
        // second: 이미 APPROVED terminal이므로 UPDATE는 miss되고, reread 결과가 intended와 같아야 한다.
        RecoveryFinalizeResult second = service.finalizeApproval(intended);

        // 첫 호출은 DB를 바꾼 주체라 APPLIED, 두 번째 호출은 같은 terminal 재확인이라 ALREADY_CONSISTENT를 기대한다.
        assertThat(first.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(second.resultType()).isEqualTo(RecoveryFinalizeResultType.ALREADY_CONSISTENT);
        // 반복 호출 후에도 row는 1건 그대로이고 최종 상태도 APPROVED에서 흔들리지 않아야 한다.
        assertThat(approvalStatus("R6P6-APP-IDEMPOTENT", 1)).isEqualTo("APPROVED");
        assertThat(countAttempts("R6P6-APP-IDEMPOTENT")).isEqualTo(1);
    }

    @Test
    @DisplayName("approval recovery는 UNKNOWN_TIMEOUT row를 DECLINED로 확정한다")
    void approval_unknown_timeout_to_declined_is_applied() {
        // finalizeApproval(): recovery 전용 SQL은 UNKNOWN_TIMEOUT approval row도 DECLINED terminal로 바꿀 수 있어야 한다.
        insertAttempt("R6P6-APP-UNKNOWN-TO-DECLINED", 1, "UNKNOWN_TIMEOUT");
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.declined(
                "R6P6-APP-UNKNOWN-TO-DECLINED",
                1,
                "05",
                "VAN-APP-R6P6-DECLINED"
        );

        // result: UNKNOWN_TIMEOUT approval row가 DECLINED terminal로 실제 UPDATE 되어야 한다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // recovery finalization이 성공했으므로 APPLIED이고, DB에도 DECLINED가 남아야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(approvalStatus("R6P6-APP-UNKNOWN-TO-DECLINED", 1)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("approval terminal row는 다른 terminal 값으로 overwrite되지 않고 conflict로 판정된다")
    void approval_terminal_row_is_not_overwritten() {
        // finalizeApproval(): 이미 APPROVED인 terminal row는 DECLINED intended로 overwrite되지 않아야 한다.
        // update miss 후 reread한 APPROVED를 기준으로 TERMINAL_CONFLICT를 반환하는지 확인한다.
        insertAttempt("R6P6-APP-CONFLICT", 1, "APPROVED");
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.declined(
                "R6P6-APP-CONFLICT",
                1,
                "05",
                "VAN-APP-CONFLICT"
        );

        // result: 이미 APPROVED라 conditional UPDATE는 miss되고, reread에서 APPROVED를 확인해야 한다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // intended DECLINED와 DB APPROVED가 충돌하므로 TERMINAL_CONFLICT를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TERMINAL_CONFLICT);
        // 가장 중요한 DB invariant: 기존 APPROVED terminal을 DECLINED로 덮어쓰면 안 된다.
        assertThat(approvalStatus("R6P6-APP-CONFLICT", 1)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("approval 대상 row가 없으면 TARGET_NOT_FOUND를 반환한다")
    void approval_missing_row_returns_TARGET_NOT_FOUND() {
        // finalizeApproval(): conditional update 대상도 없고 reread 대상도 없으면 missing target으로 판정한다.
        // result: 대상 attempt row가 없으므로 UPDATE도 miss되고 reread도 empty가 되어야 한다.
        RecoveryFinalizeResult result = service.finalizeApproval(AttemptResultUpdateParam.approved(
                "R6P6-APP-MISSING",
                1,
                "APPROVAL-MISSING",
                "VAN-APP-MISSING"
        ));

        // recovery가 적용할 target이 없다는 의미로 TARGET_NOT_FOUND와 null dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TARGET_NOT_FOUND);
        assertThat(result.dbStatus()).isNull();
    }

    @Test
    @DisplayName("cancel recovery는 PENDING row를 CANCELLED로 확정하고 반복 호출은 idempotent하다")
    void cancel_pending_to_cancelled_is_applied_and_idempotent() {
        // finalizeCancel(): 실제 PostgreSQL에서 PENDING cancel row만 CANCELLED로 확정되는지 검증한다.
        // 같은 intended 반복 호출은 reread한 CANCELLED와 비교해 ALREADY_CONSISTENT로 끝나야 한다.
        insertAttempt("R6P6-CAN-ORIGINAL-IDEMPOTENT", 1, "APPROVED");
        insertCancel("R6P6-CAN-IDEMPOTENT", "R6P6-CAN-ORIGINAL-IDEMPOTENT", 1, "PENDING");
        CancelResultUpdateParam intended = CancelResultUpdateParam.cancelled(
                "R6P6-CAN-IDEMPOTENT",
                "R6P6-CAN-ORIGINAL-IDEMPOTENT",
                1,
                "VAN-CAN-R6P6",
                "CANCEL-APPROVAL-R6P6"
        );

        // first: PENDING cancel row가 recoverable 조건에 걸려 CANCELLED로 실제 UPDATE 되어야 한다.
        RecoveryFinalizeResult first = service.finalizeCancel(intended);
        // second: 이미 CANCELLED terminal이므로 UPDATE는 miss되고, reread 결과가 intended와 같아야 한다.
        RecoveryFinalizeResult second = service.finalizeCancel(intended);

        // 첫 호출은 APPLIED, 반복 호출은 같은 terminal을 재확인한 ALREADY_CONSISTENT를 기대한다.
        assertThat(first.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(second.resultType()).isEqualTo(RecoveryFinalizeResultType.ALREADY_CONSISTENT);
        // 반복 finalization이 terminal 값을 흔들지 않고 CANCELLED를 유지해야 한다.
        assertThat(cancelStatus("R6P6-CAN-IDEMPOTENT")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel recovery는 UNKNOWN_TIMEOUT row를 CANCEL_DECLINED로 확정한다")
    void cancel_unknown_timeout_to_declined_is_applied() {
        // finalizeCancel(): recovery 전용 SQL은 UNKNOWN_TIMEOUT cancel row도 CANCEL_DECLINED로 수렴시켜야 한다.
        insertAttempt("R6P6-CAN-ORIGINAL-UNKNOWN", 1, "APPROVED");
        insertCancel("R6P6-CAN-UNKNOWN-TO-DECLINED", "R6P6-CAN-ORIGINAL-UNKNOWN", 1, "UNKNOWN_TIMEOUT");
        CancelResultUpdateParam intended = CancelResultUpdateParam.declined(
                "R6P6-CAN-UNKNOWN-TO-DECLINED",
                "R6P6-CAN-ORIGINAL-UNKNOWN",
                1,
                "VAN-CAN-DECLINED",
                "05"
        );

        // result: UNKNOWN_TIMEOUT cancel row가 CANCEL_DECLINED terminal로 실제 UPDATE 되어야 한다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // recovery finalization이 성공했으므로 APPLIED이고, DB에도 CANCEL_DECLINED가 남아야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(cancelStatus("R6P6-CAN-UNKNOWN-TO-DECLINED")).isEqualTo("CANCEL_DECLINED");
    }

    @Test
    @DisplayName("cancel terminal row는 다른 terminal 값으로 overwrite되지 않고 conflict로 판정된다")
    void cancel_terminal_row_is_not_overwritten() {
        // finalizeCancel(): 이미 CANCELLED인 row는 CANCEL_DECLINED intended로 overwrite되지 않아야 한다.
        // DB의 terminal fact를 보존하고 service는 TERMINAL_CONFLICT만 반환한다.
        insertAttempt("R6P6-CAN-ORIGINAL-CONFLICT", 1, "APPROVED");
        insertCancel("R6P6-CAN-CONFLICT", "R6P6-CAN-ORIGINAL-CONFLICT", 1, "CANCELLED");
        CancelResultUpdateParam intended = CancelResultUpdateParam.declined(
                "R6P6-CAN-CONFLICT",
                "R6P6-CAN-ORIGINAL-CONFLICT",
                1,
                "VAN-CAN-CONFLICT",
                "05"
        );

        // result: 이미 CANCELLED라 conditional UPDATE는 miss되고, reread에서 CANCELLED를 확인해야 한다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // intended CANCEL_DECLINED와 DB CANCELLED가 충돌하므로 TERMINAL_CONFLICT를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TERMINAL_CONFLICT);
        // 가장 중요한 DB invariant: 기존 CANCELLED terminal을 CANCEL_DECLINED로 덮어쓰면 안 된다.
        assertThat(cancelStatus("R6P6-CAN-CONFLICT")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel 대상 row가 없으면 TARGET_NOT_FOUND를 반환한다")
    void cancel_missing_row_returns_TARGET_NOT_FOUND() {
        // finalizeCancel(): CURRENT_TRX_NO 기준 update/reread가 모두 실패하면 recovery 대상 없음으로 판정한다.
        // result: 대상 cancel row가 없으므로 UPDATE도 miss되고 CURRENT_TRX_NO reread도 empty가 되어야 한다.
        RecoveryFinalizeResult result = service.finalizeCancel(CancelResultUpdateParam.cancelled(
                "R6P6-CAN-MISSING",
                "R6P6-CAN-ORIGINAL-MISSING",
                1,
                "VAN-CAN-MISSING",
                "CANCEL-APPROVAL-MISSING"
        ));

        // recovery가 적용할 target이 없다는 의미로 TARGET_NOT_FOUND와 null dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TARGET_NOT_FOUND);
        assertThat(result.dbStatus()).isNull();
    }

    @Test
    @DisplayName("reversal recovery는 PENDING row를 REVERSED로 확정하고 반복 호출은 idempotent하다")
    void reversal_pending_to_reversed_is_applied_and_idempotent() {
        // finalizeReversal(): 실제 PostgreSQL에서 PENDING reversal row만 REVERSED로 확정되는지 검증한다.
        // 반복 finalization은 같은 terminal row reread를 통해 ALREADY_CONSISTENT가 되어야 한다.
        insertAttempt("R6P6-REV-ORIGINAL-IDEMPOTENT", 1, "APPROVED");
        insertReversal("R6P6-REV-IDEMPOTENT", "R6P6-REV-ORIGINAL-IDEMPOTENT", 1, "PENDING");
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.reversed(
                "R6P6-REV-IDEMPOTENT",
                "R6P6-REV-ORIGINAL-IDEMPOTENT",
                1,
                "VAN-REV-R6P6",
                "REVERSAL-APPROVAL-R6P6"
        );

        // first: PENDING reversal row가 recoverable 조건에 걸려 REVERSED로 실제 UPDATE 되어야 한다.
        RecoveryFinalizeResult first = service.finalizeReversal(intended);
        // second: 이미 REVERSED terminal이므로 UPDATE는 miss되고, reread 결과가 intended와 같아야 한다.
        RecoveryFinalizeResult second = service.finalizeReversal(intended);

        // 첫 호출은 APPLIED, 반복 호출은 같은 terminal을 재확인한 ALREADY_CONSISTENT를 기대한다.
        assertThat(first.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(second.resultType()).isEqualTo(RecoveryFinalizeResultType.ALREADY_CONSISTENT);
        // 반복 finalization이 terminal 값을 흔들지 않고 REVERSED를 유지해야 한다.
        assertThat(reversalStatus("R6P6-REV-IDEMPOTENT")).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("reversal recovery는 PENDING row를 REVERSAL_DECLINED로 확정한다")
    void reversal_pending_to_declined_is_applied() {
        // finalizeReversal(): PENDING reversal row는 VAN 결과에 따라 REVERSAL_DECLINED terminal로도 확정되어야 한다.
        insertAttempt("R6P6-REV-ORIGINAL-DECLINED", 1, "APPROVED");
        insertReversal("R6P6-REV-DECLINED", "R6P6-REV-ORIGINAL-DECLINED", 1, "PENDING");
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.declined(
                "R6P6-REV-DECLINED",
                "R6P6-REV-ORIGINAL-DECLINED",
                1,
                "VAN-REV-DECLINED",
                "05"
        );

        // result: PENDING reversal row가 REVERSAL_DECLINED terminal로 실제 UPDATE 되어야 한다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // recovery finalization이 성공했으므로 APPLIED이고, DB에도 REVERSAL_DECLINED가 남아야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(reversalStatus("R6P6-REV-DECLINED")).isEqualTo("REVERSAL_DECLINED");
    }

    @Test
    @DisplayName("reversal terminal row는 다른 terminal 값으로 overwrite되지 않고 conflict로 판정된다")
    void reversal_terminal_row_is_not_overwritten() {
        // finalizeReversal(): 이미 REVERSED인 row는 REVERSAL_DECLINED intended로 overwrite되지 않아야 한다.
        // conditional update miss 후 reread 결과를 기준으로 TERMINAL_CONFLICT를 반환한다.
        insertAttempt("R6P6-REV-ORIGINAL-CONFLICT", 1, "APPROVED");
        insertReversal("R6P6-REV-CONFLICT", "R6P6-REV-ORIGINAL-CONFLICT", 1, "REVERSED");
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.declined(
                "R6P6-REV-CONFLICT",
                "R6P6-REV-ORIGINAL-CONFLICT",
                1,
                "VAN-REV-CONFLICT",
                "05"
        );

        // result: 이미 REVERSED라 conditional UPDATE는 miss되고, reread에서 REVERSED를 확인해야 한다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // intended REVERSAL_DECLINED와 DB REVERSED가 충돌하므로 TERMINAL_CONFLICT를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TERMINAL_CONFLICT);
        // 가장 중요한 DB invariant: 기존 REVERSED terminal을 REVERSAL_DECLINED로 덮어쓰면 안 된다.
        assertThat(reversalStatus("R6P6-REV-CONFLICT")).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("reversal 대상 row가 없으면 TARGET_NOT_FOUND를 반환한다")
    void reversal_missing_row_returns_TARGET_NOT_FOUND() {
        // finalizeReversal(): CURRENT_TRX_NO 기준 update/reread가 모두 실패하면 recovery 대상 없음으로 판정한다.
        // result: 대상 reversal row가 없으므로 UPDATE도 miss되고 CURRENT_TRX_NO reread도 empty가 되어야 한다.
        RecoveryFinalizeResult result = service.finalizeReversal(ReversalResultUpdateParam.reversed(
                "R6P6-REV-MISSING",
                "R6P6-REV-ORIGINAL-MISSING",
                1,
                "VAN-REV-MISSING",
                "REVERSAL-APPROVAL-MISSING"
        ));

        // recovery가 적용할 target이 없다는 의미로 TARGET_NOT_FOUND와 null dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TARGET_NOT_FOUND);
        assertThat(result.dbStatus()).isNull();
    }

    private void insertAttempt(String posTrx, int attemptSeq, String finalStatus) {
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                posTrx,
                attemptSeq,
                1000,
                "411111",
                "1111",
                "VISA",
                "fingerprint-" + posTrx,
                finalStatus
        );
    }

    private void insertCancel(String currentTrxNo, String originalTrxNo, int originalAttemptSeq, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_CANCEL (
                    CURRENT_TRX_NO,
                    ORIGINAL_TRX_NO,
                    ORIGINAL_ATTEMPT_SEQ,
                    CANCEL_STATUS
                )
                VALUES (?, ?, ?, ?)
                """,
                currentTrxNo,
                originalTrxNo,
                originalAttemptSeq,
                status
        );
    }

    private void insertReversal(String currentTrxNo, String originalTrxNo, int originalAttemptSeq, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_REVERSAL (
                    CURRENT_TRX_NO,
                    ORIGINAL_TRX_NO,
                    ORIGINAL_ATTEMPT_SEQ,
                    AMOUNT,
                    REVERSAL_STATUS
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                currentTrxNo,
                originalTrxNo,
                originalAttemptSeq,
                1000,
                status
        );
    }

    private String approvalStatus(String posTrx, int attemptSeq) {
        return jdbcTemplate.queryForObject(
                """
                SELECT FINAL_STATUS
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                String.class,
                posTrx,
                attemptSeq
        );
    }

    private String cancelStatus(String currentTrxNo) {
        return jdbcTemplate.queryForObject(
                """
                SELECT CANCEL_STATUS
                FROM PAYMENT_CANCEL
                WHERE CURRENT_TRX_NO = ?
                """,
                String.class,
                currentTrxNo
        );
    }

    private String reversalStatus(String currentTrxNo) {
        return jdbcTemplate.queryForObject(
                """
                SELECT REVERSAL_STATUS
                FROM PAYMENT_REVERSAL
                WHERE CURRENT_TRX_NO = ?
                """,
                String.class,
                currentTrxNo
        );
    }

    private int countAttempts(String posTrx) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                """,
                Integer.class,
                posTrx
        );
        return count == null ? 0 : count;
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_REVERSAL WHERE CURRENT_TRX_NO LIKE ? OR ORIGINAL_TRX_NO LIKE ?",
                TEST_PREFIX + "%",
                TEST_PREFIX + "%"
        );
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO LIKE ? OR ORIGINAL_TRX_NO LIKE ?",
                TEST_PREFIX + "%",
                TEST_PREFIX + "%"
        );
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX LIKE ?",
                TEST_PREFIX + "%"
        );
    }
}
