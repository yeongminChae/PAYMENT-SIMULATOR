package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.ReversalResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ReversalRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ReversalResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentReversalService;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers
@SpringBootTest
@Import(PaymentReversalServiceImplTest.VanGatewayTestConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/payment-reversal-service-test.log"
})
class PaymentReversalServiceImplTest {

    private static final String ORIGINAL_POS_TRX = "2376-20260806-9911-5401";
    private static final String REVERSAL_POS_TRX = "2376-20260806-9911-6401";
    private static final String REVERSAL_POS_TRX_2 = "2376-20260806-9911-6402";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final int AMOUNT = 10_000;
    private static final String REVERSAL_APPROVAL_NO = "R888000001";
    private static final String VAN_REVERSAL_TRX_ID = "VAN-REVERSAL-TEST-001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_reversal_test")
                    .withUsername("payment_sim_reversal_test")
                    .withPassword("payment_sim_reversal_test");

    @Autowired
    private PaymentReversalService reversalService;

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
    void UNKNOWN_TIMEOUT_원승인은_PENDING_생성후_VAN_1회_호출하고_REVERSED로_확정한다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        ReversalResponse response = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertAll(
                () -> assertThat(response.reversalStatus()).isEqualTo(ReversalResultStatus.REVERSED),
                () -> assertThat(response.reversalApprovalNo()).isEqualTo(REVERSAL_APPROVAL_NO),
                () -> assertThat(vanGateway.reversalCount()).isEqualTo(1),
                () -> assertThat(storedReversalStatus()).isEqualTo(ReversalStatus.REVERSED.name()),
                () -> assertThat(storedReversalApprovalNo()).isEqualTo(REVERSAL_APPROVAL_NO)
        );
    }

    @Test
    void APPROVED_원승인은_REVERSAL_NOT_ALLOWED이고_VAN을_호출하지_않는다() {
        assertNotAllowed(PaymentFinalStatus.APPROVED);
    }

    @Test
    void DECLINED_원승인은_REVERSAL_NOT_ALLOWED이고_VAN을_호출하지_않는다() {
        assertNotAllowed(PaymentFinalStatus.DECLINED);
    }

    @Test
    void PROCESSING_원승인은_REVERSAL_NOT_ALLOWED이고_VAN을_호출하지_않는다() {
        insertProcessingAttempt();

        ReversalResponse response = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertAll(
                () -> assertThat(response.reversalStatus()).isEqualTo(ReversalResultStatus.REVERSAL_NOT_ALLOWED),
                () -> assertThat(response.declineCode()).isEqualTo("ORIGINAL_NOT_REVERSIBLE"),
                () -> assertThat(vanGateway.reversalCount()).isZero(),
                () -> assertThat(countReversalRows()).isZero()
        );
    }

    @Test
    void 같은_reversalPosTrx_replay는_DB_결과를_재응답하고_VAN을_재호출하지_않는다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        ReversalResponse first = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));
        ReversalResponse replay = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertAll(
                () -> assertThat(first.reversalStatus()).isEqualTo(ReversalResultStatus.REVERSED),
                () -> assertThat(replay.reversalStatus()).isEqualTo(ReversalResultStatus.REVERSED),
                () -> assertThat(replay.reversalApprovalNo()).isEqualTo(first.reversalApprovalNo()),
                () -> assertThat(vanGateway.reversalCount()).isEqualTo(1),
                () -> assertThat(countReversalRows()).isEqualTo(1)
        );
    }

    @Test
    void 같은_reversalPosTrx_다른_payload는_CONFLICT다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertThatThrownBy(() ->
                reversalService.reversal(new ReversalRequest(
                        REVERSAL_POS_TRX,
                        "2376-20260806-9911-5402",
                        ORIGINAL_ATTEMPT_SEQ
                ))
        )
                .isInstanceOf(BusinessException.class)
                .extracting("resultCode")
                .isEqualTo(ResultCode.CONFLICT);
    }

    @Test
    void 기존_REVERSED_original에_다른_reversalPosTrx가_오면_ALREADY_REVERSED다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        ReversalResponse response = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX_2));

        assertAll(
                () -> assertThat(response.reversalStatus()).isEqualTo(ReversalResultStatus.ALREADY_REVERSED),
                () -> assertThat(response.reversalPosTrx()).isEqualTo(REVERSAL_POS_TRX_2),
                () -> assertThat(response.reversalApprovalNo()).isEqualTo(REVERSAL_APPROVAL_NO),
                () -> assertThat(vanGateway.reversalCount()).isEqualTo(1)
        );
    }

    @Test
    void VAN_REVERSAL_DECLINED는_DB_REVERSAL_DECLINED로_확정한다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        vanGateway.decline(VanDeclineCode.ORIGINAL_MISMATCH);

        ReversalResponse response = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertAll(
                () -> assertThat(response.reversalStatus()).isEqualTo(ReversalResultStatus.REVERSAL_DECLINED),
                () -> assertThat(response.declineCode()).isEqualTo("ORIGINAL_MISMATCH"),
                () -> assertThat(storedReversalStatus()).isEqualTo(ReversalStatus.REVERSAL_DECLINED.name()),
                () -> assertThat(storedDeclineCode()).isEqualTo("ORIGINAL_MISMATCH")
        );
    }

    @Test
    void request_not_sent면_PENDING을_cleanup하고_RETRY_LATER를_반환한다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        vanGateway.requestNotSent();

        ReversalResponse response = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertAll(
                () -> assertThat(response.reversalStatus()).isEqualTo(ReversalResultStatus.RETRY_LATER),
                () -> assertThat(vanGateway.reversalCount()).isEqualTo(1),
                () -> assertThat(countReversalRows()).isZero()
        );
    }

    @Test
    void response_timeout이면_PENDING을_유지하고_RETRY_LATER를_반환한다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        vanGateway.timeout();

        ReversalResponse response = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertAll(
                () -> assertThat(response.reversalStatus()).isEqualTo(ReversalResultStatus.RETRY_LATER),
                () -> assertThat(vanGateway.reversalCount()).isEqualTo(1),
                () -> assertThat(storedReversalStatus()).isEqualTo(ReversalStatus.PENDING.name())
        );
    }

    @Test
    void Reversal_성공후_원_PAYMENT_ATTEMPT_status는_UNKNOWN_TIMEOUT_그대로다() {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertThat(storedOriginalAttemptStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT.name());
    }

    @Test
    @DisplayName("동시 같은 original Reversal은 VAN 호출 1회로 수렴한다")
    void concurrentSameOriginalReversal_shouldCallVanOnce() throws Exception {
        insertOriginalAttempt(PaymentFinalStatus.UNKNOWN_TIMEOUT);

        int requestCount = 12;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReversalResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                String reversalPosTrx = "2376-20260806-9911-65%02d".formatted(i);
                futures.add(executor.submit(reversalTask(ready, start, reversalPosTrx)));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ReversalResponse> responses = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<ReversalResponse> future : futures) {
                try {
                    responses.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failures.add(e);
                }
            }

            assertAll(
                    () -> assertThat(failures).isEmpty(),
                    () -> assertThat(responses).hasSize(requestCount),
                    () -> assertThat(responses.stream()
                            .filter(response -> response.reversalStatus() == ReversalResultStatus.REVERSED)
                            .count()).isEqualTo(1),
                    () -> assertThat(responses.stream()
                            .filter(response ->
                                    response.reversalStatus() == ReversalResultStatus.ALREADY_REVERSED
                                            || response.reversalStatus() == ReversalResultStatus.RETRY_LATER)
                            .count()).isEqualTo(requestCount - 1),
                    () -> assertThat(vanGateway.reversalCount()).isEqualTo(1),
                    () -> assertThat(countReversalRows()).isEqualTo(1)
            );
        } finally {
            executor.shutdown();
            if (executor.awaitTermination(10, TimeUnit.SECONDS) == false) {
                executor.shutdownNow();
                fail("ExecutorService did not terminate within timeout");
            }
        }
    }

    private Callable<ReversalResponse> reversalTask(
            CountDownLatch ready,
            CountDownLatch start,
            String reversalPosTrx
    ) {
        return () -> {
            ready.countDown();
            start.await();
            return reversalService.reversal(reversalRequest(reversalPosTrx));
        };
    }

    private void assertNotAllowed(PaymentFinalStatus finalStatus) {
        insertOriginalAttempt(finalStatus);

        ReversalResponse response = reversalService.reversal(reversalRequest(REVERSAL_POS_TRX));

        assertAll(
                () -> assertThat(response.reversalStatus()).isEqualTo(ReversalResultStatus.REVERSAL_NOT_ALLOWED),
                () -> assertThat(response.declineCode()).isEqualTo("ORIGINAL_NOT_REVERSIBLE"),
                () -> assertThat(vanGateway.reversalCount()).isZero(),
                () -> assertThat(countReversalRows()).isZero()
        );
    }

    private ReversalRequest reversalRequest(String reversalPosTrx) {
        return new ReversalRequest(reversalPosTrx, ORIGINAL_POS_TRX, ORIGINAL_ATTEMPT_SEQ);
    }

    private void insertOriginalAttempt(PaymentFinalStatus finalStatus) {
        insertAttempt(finalStatus.name());
    }

    private void insertProcessingAttempt() {
        insertAttempt(null);
    }

    private void insertAttempt(String finalStatus) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_ATTEMPT_SEQ (POS_TRX, LAST_SEQ)
                VALUES (?, ?)
                """,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ
        );
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
                    DECLINE_CODE,
                    VAN_TRX_ID
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                AMOUNT,
                "42424242",
                "4242",
                "VISA",
                "fingerprint",
                finalStatus,
                null,
                finalStatus == null ? null : "TIMEOUT",
                "VAN-ORIGINAL-UNKNOWN"
        );
    }

    private int countReversalRows() {
        return count("SELECT COUNT(*) FROM PAYMENT_REVERSAL WHERE ORIGINAL_TRX_NO = ?", ORIGINAL_POS_TRX);
    }

    private String storedReversalStatus() {
        return text("SELECT REVERSAL_STATUS FROM PAYMENT_REVERSAL WHERE ORIGINAL_TRX_NO = ?", ORIGINAL_POS_TRX);
    }

    private String storedReversalApprovalNo() {
        return text("SELECT REVERSAL_APPROVAL_NO FROM PAYMENT_REVERSAL WHERE ORIGINAL_TRX_NO = ?", ORIGINAL_POS_TRX);
    }

    private String storedDeclineCode() {
        return text("SELECT DECLINE_CODE FROM PAYMENT_REVERSAL WHERE ORIGINAL_TRX_NO = ?", ORIGINAL_POS_TRX);
    }

    private String storedOriginalAttemptStatus() {
        return text("SELECT FINAL_STATUS FROM PAYMENT_ATTEMPT WHERE POS_TRX = ? AND ATTEMPT_SEQ = 1", ORIGINAL_POS_TRX);
    }

    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, args));
    }

    private String text(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM PAYMENT_REVERSAL WHERE ORIGINAL_TRX_NO = ?", ORIGINAL_POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_CANCEL WHERE ORIGINAL_TRX_NO = ?", ORIGINAL_POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", ORIGINAL_POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", ORIGINAL_POS_TRX);
        jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?", ORIGINAL_POS_TRX);
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

        private final AtomicInteger reversalCount = new AtomicInteger();
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);
        private final AtomicReference<VanDeclineCode> declineCode =
                new AtomicReference<>(VanDeclineCode.ORIGINAL_MISMATCH);

        @Override
        public VanApproveResponse approve(VanApproveRequest request) {
            throw new UnsupportedOperationException("approve is not used in this test");
        }

        @Override
        public VanCancelResponse cancel(VanCancelRequest request) {
            throw new UnsupportedOperationException("cancel is not used in this test");
        }

        @Override
        public VanReversalResponse reversal(VanReversalRequest request) {
            reversalCount.incrementAndGet();

            return switch (mode.get()) {
                case SUCCESS -> VanReversalResponse.builder()
                        .reversalPosTrx(request.reversalPosTrx())
                        .originalPosTrx(request.originalPosTrx())
                        .originalAttemptSeq(request.originalAttemptSeq())
                        .reversalStatus(VanReversalStatus.REVERSED)
                        .resultCode(VanReversalResultCode.SUCCESS)
                        .reversalApprovalNo(REVERSAL_APPROVAL_NO)
                        .declineCode(null)
                        .vanReversalTrxId(VAN_REVERSAL_TRX_ID)
                        .respondedAt(LocalDateTime.now())
                        .build();
                case DECLINED -> VanReversalResponse.builder()
                        .reversalPosTrx(request.reversalPosTrx())
                        .originalPosTrx(request.originalPosTrx())
                        .originalAttemptSeq(request.originalAttemptSeq())
                        .reversalStatus(VanReversalStatus.REVERSAL_DECLINED)
                        .resultCode(VanReversalResultCode.ORIGINAL_MISMATCH)
                        .reversalApprovalNo(null)
                        .declineCode(declineCode.get())
                        .vanReversalTrxId(VAN_REVERSAL_TRX_ID)
                        .respondedAt(LocalDateTime.now())
                        .build();
                case REQUEST_NOT_SENT -> throw new VanGatewayRequestNotSentException(
                        new RuntimeException("connect failed")
                );
                case TIMEOUT -> throw new VanGatewayTimeoutException(
                        new RuntimeException("timeout")
                );
            };
        }

        @Override
        public VanInquiryResponse inquiry(VanInquiryRequest request) {
            throw new UnsupportedOperationException("inquiry is not used in this test");
        }

        void decline(VanDeclineCode declineCode) {
            this.declineCode.set(declineCode);
            mode.set(Mode.DECLINED);
        }

        void requestNotSent() {
            mode.set(Mode.REQUEST_NOT_SENT);
        }

        void timeout() {
            mode.set(Mode.TIMEOUT);
        }

        void reset() {
            reversalCount.set(0);
            mode.set(Mode.SUCCESS);
            declineCode.set(VanDeclineCode.ORIGINAL_MISMATCH);
        }

        int reversalCount() {
            return reversalCount.get();
        }

        enum Mode {
            SUCCESS,
            DECLINED,
            REQUEST_NOT_SENT,
            TIMEOUT
        }
    }
}
