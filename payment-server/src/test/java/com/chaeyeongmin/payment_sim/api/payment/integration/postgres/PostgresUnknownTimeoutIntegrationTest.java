package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.InquiryRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.InquiryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentInquiryService;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PostgreSQL UNKNOWN_TIMEOUT 승인 후속조회 통합 테스트.
 *
 * <p>이 테스트는 HTTP 계층보다 승인/조회 유스케이스와 PostgreSQL 저장 상태에 초점을 둔다.
 * 승인 서비스는 VAN 승인 결과가 UNKNOWN_TIMEOUT이어도 즉시 inquiry를 호출하지 않고,
 * PAYMENT_ATTEMPT에 UNKNOWN_TIMEOUT을 저장한 뒤 응답해야 한다.
 *
 * <p>후속 확정은 조회 서비스 책임이다. 조회 서비스는 UNKNOWN_TIMEOUT row를 만났을 때만
 * VAN inquiry를 호출하고, VAN이 APPROVED/DECLINED를 반환하면 PostgreSQL의 조건부 update로
 * 저장 상태를 확정한다. 이미 확정된 row는 DB 결과를 재사용해야 하므로 VAN inquiry가 반복되면 안 된다.
 */
@Testcontainers
@SpringBootTest
@Import(PostgresUnknownTimeoutIntegrationTest.VanGatewayTestConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/postgres-unknown-timeout-it.log"
})
class PostgresUnknownTimeoutIntegrationTest {

    private static final String POS_TRX = "2376-20260806-9911-3301";
    private static final int AMOUNT = 10000;
    private static final String PAN = "4242424242424242";
    private static final String EXPIRY_YY_MM = "2812";
    private static final String APPROVAL_NO = "A777000002";
    private static final String VAN_TRX_ID = "VAN-TRX-UNKNOWN-TIMEOUT";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_unknown_timeout_it")
                    .withUsername("payment_sim_unknown_timeout_it")
                    .withPassword("payment_sim_unknown_timeout_it");

    @Autowired
    private PaymentApprovalService approvalService;

    @Autowired
    private PaymentInquiryService inquiryService;

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
    @DisplayName("UNKNOWN_TIMEOUT 승인건은 inquiry로 APPROVED 확정되고 이후 DB 결과를 재사용한다")
    void unknownTimeoutIsFinalizedToApprovedByInquiry() {
        /*
         * Given
         * - approve VAN 응답은 항상 UNKNOWN_TIMEOUT이다.
         * - inquiry VAN 응답은 APPROVED로 설정한다.
         *
         * When
         * - 동일 승인 요청을 2회 보낸다.
         * - 같은 attempt를 inquiry로 2회 조회한다.
         *
         * Then
         * - 승인 재요청은 기존 UNKNOWN_TIMEOUT row를 재사용하고 approve VAN을 다시 호출하지 않는다.
         * - 첫 inquiry만 VAN inquiry를 호출해 DB를 APPROVED로 확정한다.
         * - 두 번째 inquiry는 확정된 DB 결과만 재응답한다.
         */
        vanGateway.inquiryFinalStatus(PaymentFinalStatus.APPROVED);

        // 최초 approve는 UNKNOWN_TIMEOUT row를 만든다. 승인 서비스 안에서 inquiry는 호출하지 않는다.
        ApproveResponse approve = approvalService.approve(approveRequest());

        // 같은 payload의 approve 재요청은 멱등 재응답이어야 한다.
        ApproveResponse duplicatedApprove = approvalService.approve(approveRequest());
        InquiryRequest inquiryRequest = new InquiryRequest(POS_TRX, approve.attemptSeq());

        // 첫 inquiry는 UNKNOWN_TIMEOUT row를 VAN 조회 대상으로 삼아 APPROVED로 확정한다.
        InquiryResponse firstInquiry = inquiryService.inquiry(inquiryRequest);

        // 이미 APPROVED로 확정된 뒤에는 DB 재응답이어야 하므로 VAN inquiry 호출 수가 늘면 안 된다.
        InquiryResponse duplicatedInquiry = inquiryService.inquiry(inquiryRequest);

        assertAll(
                () -> assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, approve.finalStatus()),
                () -> assertEquals(approve.attemptSeq(), duplicatedApprove.attemptSeq()),
                () -> assertEquals(firstInquiry.attemptSeq(), duplicatedApprove.attemptSeq()),
                () -> assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, duplicatedApprove.finalStatus()),
                () -> assertEquals(PaymentFinalStatus.APPROVED, firstInquiry.finalStatus()),
                () -> assertEquals(APPROVAL_NO, firstInquiry.approvalNo()),
                () -> assertEquals(PaymentFinalStatus.APPROVED, duplicatedInquiry.finalStatus()),
                () -> assertEquals(firstInquiry.attemptSeq(), duplicatedInquiry.attemptSeq()),
                () -> assertEquals(firstInquiry.approvalNo(), duplicatedInquiry.approvalNo()),
                () -> assertEquals(1, vanGateway.approveCount()),
                () -> assertEquals(1, vanGateway.inquiryCount())
        );
        assertStoredAttempt(PaymentFinalStatus.APPROVED, APPROVAL_NO);
    }

    @Test
    @DisplayName("UNKNOWN_TIMEOUT 승인건은 VAN inquiry도 미확정이면 DB 상태를 유지한다")
    void unknownTimeoutRemainsUnknownWhenVanInquiryIsStillUnknown() {
        /*
         * Given
         * - approve VAN 응답은 항상 UNKNOWN_TIMEOUT이다.
         * - inquiry VAN 응답도 UNKNOWN_TIMEOUT으로 설정한다.
         *
         * When
         * - 동일 승인 요청을 2회 보낸 뒤 inquiry를 호출한다.
         *
         * Then
         * - approve VAN은 최초 1회만 호출된다.
         * - inquiry VAN은 1회 호출되지만, 여전히 미확정이면 DB를 흔들지 않는다.
         * - PAYMENT_ATTEMPT는 UNKNOWN_TIMEOUT과 approvalNo=null 상태를 유지한다.
         */
        vanGateway.inquiryFinalStatus(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        // approve 단계는 UNKNOWN_TIMEOUT 저장까지만 담당한다.
        ApproveResponse approve = approvalService.approve(approveRequest());

        // 중복 approve가 새 attempt나 VAN 재승인으로 이어지지 않는지 고정한다.
        ApproveResponse duplicatedApprove = approvalService.approve(approveRequest());

        // VAN inquiry도 미확정이면 조회 서비스는 응답만 UNKNOWN_TIMEOUT으로 내리고 DB 상태는 유지한다.
        InquiryResponse inquiry = inquiryService.inquiry(new InquiryRequest(POS_TRX, approve.attemptSeq()));

        assertAll(
                () -> assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, approve.finalStatus()),
                () -> assertEquals(1, approve.attemptSeq()),
                () -> assertEquals(approve.attemptSeq(), duplicatedApprove.attemptSeq()),
                () -> assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, duplicatedApprove.finalStatus()),
                () -> assertNull(duplicatedApprove.approvalNo()),
                () -> assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, inquiry.finalStatus()),
                () -> assertEquals(1, inquiry.attemptSeq()),
                () -> assertNull(inquiry.approvalNo()),
                () -> assertEquals(1, vanGateway.approveCount()),
                () -> assertEquals(1, vanGateway.inquiryCount())
        );
        assertStoredAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT, null);
    }

    private ApproveRequest approveRequest() {
        return new ApproveRequest(POS_TRX, AMOUNT, new CardInput(PAN, EXPIRY_YY_MM));
    }

    private void assertStoredAttempt(PaymentFinalStatus finalStatus, String approvalNo) {
        // 응답만 맞고 DB가 틀어진 회귀를 막기 위해 저장 row 수와 최종 컬럼을 함께 확인한다.
        assertAll(
                () -> assertEquals(1, count("SELECT COUNT(*) FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?")),
                () -> assertEquals(1, count("SELECT COUNT(*) FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?")),
                () -> assertEquals(1, count("SELECT COUNT(*) FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?")),
                () -> assertEquals(1, intValue("SELECT LAST_SEQ FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?")),
                () -> assertEquals(finalStatus.name(), textValue("SELECT FINAL_STATUS FROM PAYMENT_ATTEMPT WHERE POS_TRX = ? AND ATTEMPT_SEQ = 1")),
                () -> assertEquals(approvalNo, textValue("SELECT APPROVAL_NO FROM PAYMENT_ATTEMPT WHERE POS_TRX = ? AND ATTEMPT_SEQ = 1"))
        );
    }

    private int count(String sql) {
        return intValue(sql);
    }

    private int intValue(String sql) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, POS_TRX));
    }

    private String textValue(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class, POS_TRX);
    }

    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM PAYMENT_EVENT_LOG WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?", POS_TRX);
    }

    @TestConfiguration
    static class VanGatewayTestConfiguration {

        @Bean
        @Primary
        CountingVanGateway countingVanGateway() {
            return new CountingVanGateway();
        }
    }

    static class CountingVanGateway implements VanGateway {

        private final AtomicInteger approveCount = new AtomicInteger();
        private final AtomicInteger inquiryCount = new AtomicInteger();
        private PaymentFinalStatus inquiryFinalStatus = PaymentFinalStatus.UNKNOWN_TIMEOUT;

        // approve는 테스트 시나리오상 항상 UNKNOWN_TIMEOUT을 반환한다.
        // 이 고정 응답 덕분에 후속 확정 책임이 승인 서비스가 아니라 조회 서비스에 있음을 검증할 수 있다.
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
                    .message("OK")
                    .respondedAt(LocalDateTime.now())
                    .build();
        }

        // inquiry 결과는 테스트별로 APPROVED 또는 UNKNOWN_TIMEOUT으로 바꿔
        // "확정되는 미확정 거래"와 "계속 미확정인 거래"를 같은 fake로 재현한다.
        @Override
        public VanInquiryResponse inquiry(VanInquiryRequest request) {
            inquiryCount.incrementAndGet();
            return VanInquiryResponse.builder()
                    .targetType(VanInquiryTargetType.APPROVAL)
                    .targetTrxNo(request.targetTrxNo())
                    .targetAttemptSeq(request.targetAttemptSeq())
                    .resultCode(VanInquiryResultCode.SUCCESS)
                    .status(toInquiryStatus(inquiryFinalStatus))
                    .approvalNo(inquiryFinalStatus == PaymentFinalStatus.APPROVED ? APPROVAL_NO : null)
                    .cancelApprovalNo(null)
                    .declineCode(inquiryFinalStatus == PaymentFinalStatus.APPROVED ? null : VanDeclineCode.TIMEOUT)
                    .vanTrxId(request.vanTrxId())
                    .message("OK")
                    .respondedAt(LocalDateTime.now())
                    .build();
        }

        @Override
        public VanCancelResponse cancel(VanCancelRequest request) {
            throw new UnsupportedOperationException("cancel is not used in this test");
        }

        void inquiryFinalStatus(PaymentFinalStatus inquiryFinalStatus) {
            this.inquiryFinalStatus = inquiryFinalStatus;
        }

        private VanInquiryStatus toInquiryStatus(PaymentFinalStatus finalStatus) {
            return switch (finalStatus) {
                case APPROVED -> VanInquiryStatus.APPROVED;
                case DECLINED -> VanInquiryStatus.DECLINED;
                case UNKNOWN_TIMEOUT -> VanInquiryStatus.UNKNOWN;
                case PROCESSING -> throw new IllegalArgumentException(
                        "PROCESSING is not a VAN inquiry terminal status"
                );
            };
        }

        void reset() {
            approveCount.set(0);
            inquiryCount.set(0);
            inquiryFinalStatus = PaymentFinalStatus.UNKNOWN_TIMEOUT;
        }

        int approveCount() {
            return approveCount.get();
        }

        int inquiryCount() {
            return inquiryCount.get();
        }
    }
}
