package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.*;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
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

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Import(PostgresInquiryRecoveryRaceIntegrationTest.RaceVanGatewayConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/postgres-inquiry-recovery-race-it.log"
})
class PostgresInquiryRecoveryRaceIntegrationTest {

    private static final String POS_TRX = "R6P13-INQUIRY-RECOVERY-001";

    private static final int AMOUNT = 10000;
    private static final String PAN = "4242424242424242";
    private static final String EXPIRY_YY_MM = "2812";
    private static final String APPROVAL_NO = "A-R6P13-001";
    private static final String VAN_TRX_ID = "VAN-R6P13-001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_inquiry_recovery_race_it")
                    .withUsername("payment_sim_inquiry_recovery_race_it")
                    .withPassword("payment_sim_inquiry_recovery_race_it");

    @Autowired
    private PaymentApprovalService approvalService;

    @Autowired
    private PaymentInquiryService inquiryService;

    @Autowired
    private RecoveryFinalizationService recoveryFinalizationService;

    @Autowired
    private RaceVanGateway vanGateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void inquiryWins_recoveryConvergesAsAlreadyConsistent() {

        // 1. 최초 승인은 UNKNOWN_TIMEOUT
        ApproveResponse approve = approvalService.approve(approveRequest());

        assertThat(approve.finalStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        // 2. 사람이 Inquiry → VAN APPROVED → DB APPROVED
        InquiryResponse inquiry = inquiryService.inquiry(new InquiryRequest(POS_TRX, approve.attemptSeq()));

        assertThat(inquiry.finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);

        // 3. Recovery도 이미 APPROVED fact를 얻었다고 가정
        AttemptResultUpdateParam intended =
                AttemptResultUpdateParam.approved(
                        POS_TRX,
                        approve.attemptSeq(),
                        APPROVAL_NO,
                        VAN_TRX_ID
                );

        RecoveryFinalizeResult recoveryResult = recoveryFinalizationService.finalizeApproval(intended);

        // 4. Recovery는 덮어쓰지 않고 같은 terminal임을 확인
        assertThat(recoveryResult.resultType()).isEqualTo(RecoveryFinalizeResultType.ALREADY_CONSISTENT);
        assertThat(recoveryResult.intendedStatus()).isEqualTo("APPROVED");
        assertThat(recoveryResult.dbStatus()).isEqualTo("APPROVED");
        assertThat(storedStatus()).isEqualTo("APPROVED");
        assertThat(countAttempts()).isEqualTo(1);
        assertThat(vanGateway.approveCount()).isEqualTo(1);
        assertThat(vanGateway.inquiryCount()).isEqualTo(1);
    }

    @Test
    void recoveryWinsWhileInquiryIsCallingVan_inquiryRereadsApproved() {

        ApproveResponse approve = approvalService.approve(approveRequest());

        assertThat(approve.finalStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        AtomicReference<RecoveryFinalizeResult> recoveryResult = new AtomicReference<>();

        /*
         * Human Inquiry가 이미 UNKNOWN_TIMEOUT을 읽고 VAN Inquiry까지 보낸 시점에
         * Recovery Worker가 먼저 같은 거래를 APPROVED로 확정했다고 가정한다.
         *
         * 이후 Human Inquiry가 VAN APPROVED 응답을 받아 DB update를 시도하면
         * UNKNOWN_TIMEOUT 조건이 더 이상 성립하지 않아 update miss가 발생해야 하고,
         * DB reread를 통해 Recovery가 저장한 APPROVED로 수렴해야 한다.
         */
        vanGateway.beforeInquiryResponse(request -> {

            AttemptResultUpdateParam intended =
                    AttemptResultUpdateParam.approved(
                            POS_TRX,
                            approve.attemptSeq(),
                            APPROVAL_NO,
                            VAN_TRX_ID
                    );

            recoveryResult.set(recoveryFinalizationService.finalizeApproval(intended));
        });

        InquiryResponse inquiry = inquiryService.inquiry(new InquiryRequest(POS_TRX, approve.attemptSeq()));

        assertThat(recoveryResult.get()).isNotNull();
        assertThat(recoveryResult.get().resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(recoveryResult.get().dbStatus()).isEqualTo("APPROVED");
        assertThat(inquiry.finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);
        assertThat(inquiry.approvalNo()).isEqualTo(APPROVAL_NO);
        assertThat(storedStatus()).isEqualTo("APPROVED");
        assertThat(countAttempts()).isEqualTo(1);
        assertThat(vanGateway.approveCount()).isEqualTo(1);
        assertThat(vanGateway.inquiryCount()).isEqualTo(1);
    }

    private ApproveRequest approveRequest() {
        return new ApproveRequest(POS_TRX, AMOUNT, new CardInput(PAN, EXPIRY_YY_MM));
    }

    private String storedStatus() {
        return jdbcTemplate.queryForObject(
                """
                SELECT FINAL_STATUS
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = 1
                """,
                String.class,
                POS_TRX
        );
    }

    private int countAttempts() {
        return Objects.requireNonNull(
                jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM PAYMENT_ATTEMPT
                    WHERE POS_TRX = ?
                    """,
                    Integer.class,
                    POS_TRX
            )
        );
    }

    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM PAYMENT_EVENT_LOG WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?", POS_TRX);
    }

    @TestConfiguration
    static class RaceVanGatewayConfig {

        @Bean
        @Primary
        RaceVanGateway raceVanGateway() {
            return new RaceVanGateway();
        }
    }

    static class RaceVanGateway implements VanGateway {

        private final AtomicInteger approveCount = new AtomicInteger();
        private final AtomicInteger inquiryCount = new AtomicInteger();

        private Consumer<VanInquiryRequest> beforeInquiryResponse = request -> {};

        @Override
        public VanApproveResponse approve(VanApproveRequest request) {
            approveCount.incrementAndGet();

            return VanApproveResponse.builder()
                    .posTrx(request.posTrx())
                    .attemptSeq(request.attemptSeq())
                    .cardBin(request.cardBin())
                    .cardLast4(request.cardLast4())
                    .finalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT)
                    .approvalNo(null)
                    .declineCode(VanDeclineCode.TIMEOUT)
                    .vanTrxId(VAN_TRX_ID)
                    .message("TIMEOUT")
                    .respondedAt(LocalDateTime.now())
                    .build();
        }

        @Override
        public VanInquiryResponse inquiry(VanInquiryRequest request) {

            inquiryCount.incrementAndGet();

            // 여기서 Recovery를 먼저 실행시키는 hook
            beforeInquiryResponse.accept(request);

            return VanInquiryResponse.builder()
                    .targetType(VanInquiryTargetType.APPROVAL)
                    .targetTrxNo(request.targetTrxNo())
                    .targetAttemptSeq(request.targetAttemptSeq())
                    .resultCode(VanInquiryResultCode.SUCCESS)
                    .status(VanInquiryStatus.APPROVED)
                    .approvalNo(APPROVAL_NO)
                    .declineCode(null)
                    .vanTrxId(VAN_TRX_ID)
                    .message("OK")
                    .respondedAt(LocalDateTime.now())
                    .build();
        }

        @Override
        public VanCancelResponse cancel(VanCancelRequest request) {
            throw new UnsupportedOperationException("cancel is not used in this test");
        }

        @Override
        public VanReversalResponse reversal(VanReversalRequest request) {
            throw new UnsupportedOperationException("reversal is not used in this test");
        }

        // VAN Inquiry 응답 직전에 원하는 경합 동작을 삽입하기 위한 test hook
        void beforeInquiryResponse(Consumer<VanInquiryRequest> action) {
            this.beforeInquiryResponse = action;
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
            beforeInquiryResponse = request -> {};
        }
    }

}
