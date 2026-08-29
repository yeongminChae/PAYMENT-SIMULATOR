package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PostgreSQL 동일 승인 요청 동시성 멱등성 테스트.
 *
 * <p>HTTP 매핑보다 승인 유스케이스의 핵심 정합성에 초점을 둔다. 따라서 Controller/MockMvc 대신
 * {@link PaymentApprovalService}를 직접 호출해 Service -> Repository -> MyBatis -> PostgreSQL -> VAN 경로를 검증한다.
 *
 * <p>VAN 호출 횟수는 테스트 전용 {@link CountingVanGateway}가 thread-safe하게 기록한다. 이 Fake는 Spring Bean으로
 * 등록되어 실제 승인 서비스가 주입받는 {@link VanGateway}를 대체하므로, 테스트가 관찰하는 횟수와 서비스가 호출한
 * 횟수가 같은 지점을 가리킨다.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/postgres-approval-concurrency-it.log"
})
class PostgresApprovalConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 20;
    private static final String POS_TRX = "2376-20260806-9911-3201";
    private static final int AMOUNT = 10000;
    private static final String PAN = "4242424242424242";
    private static final String EXPIRY_YY_MM = "2812";
    private static final String APPROVAL_NO = "A777000001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_approval_concurrency_it")
                    .withUsername("payment_sim_approval_concurrency_it")
                    .withPassword("payment_sim_approval_concurrency_it");

    @Autowired
    private PaymentApprovalService approvalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CountingVanGateway vanGateway;

    @BeforeEach
    void cleanBefore() {
        cleanupTestData();
        vanGateway.reset();
    }

    @AfterEach
    void cleanAfter() {
        cleanupTestData();
        vanGateway.reset();
    }

    /**
     * [시나리오]
     * - Given: PostgreSQL에 테스트 posTrx 승인 attempt와 attempt sequence가 없는 상태다.
     * - When : 동일 posTrx/amount/card payload로 승인 요청 20개를 동시에 실행한다.
     * - Then : 모든 작업은 성공 응답을 반환하고 attemptSeq와 approvalNo는 모두 동일하다.
     * - And  : PAYMENT_ATTEMPT, PAYMENT_EXTERNAL_INFO는 각각 1건만 남는다.
     * - And  : PAYMENT_ATTEMPT_SEQ는 해당 posTrx 기준 1 row이고 최종 LAST_SEQ는 1이다.
     * - And  : VAN approve 호출은 정확히 1회다.
     *
     * [검증 의도]
     * - DB row 수만으로 멱등성을 판단하지 않고, 외부 승인 부작용인 VAN 호출 횟수까지 함께 고정한다.
     * - 테스트는 성능 측정이 아니라 동시 최초 요청에서 중복 승인 위험이 없는지 확인하는 정합성 검증이다.
     */
    @Test
    @DisplayName("PostgreSQL 동일 승인 동시 요청은 VAN 호출과 승인 row를 1회로 유지한다")
    void approveSameRequestConcurrently_shouldReuseSingleApprovalResultAndCallVanOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApproveResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < REQUEST_COUNT; i++) {
                futures.add(executor.submit(approveTask(ready, start)));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Timed out while waiting for all approve tasks to become ready"
            );

            start.countDown();

            List<ApproveResponse> responses = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<ApproveResponse> future : futures) {
                try {
                    responses.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failures.add(e);
                }
            }

            if (failures.isEmpty() == false) {
                AssertionError error = new AssertionError("Approve tasks failed: " + failures.size());
                failures.forEach(error::addSuppressed);
                throw error;
            }

            assertEquals(REQUEST_COUNT, responses.size());
            assertTrue(responses.stream().allMatch(response ->
                    response.finalStatus() == PaymentFinalStatus.APPROVED
                            || response.finalStatus() == PaymentFinalStatus.PROCESSING
            ));
            assertTrue(responses.stream().anyMatch(response -> response.finalStatus() == PaymentFinalStatus.APPROVED));

            Set<Integer> attemptSeqs = responses.stream()
                    .map(ApproveResponse::attemptSeq)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> approvalNos = responses.stream()
                    .map(ApproveResponse::approvalNo)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            int paymentAttemptCount = countPaymentAttempts();
            int externalInfoCount = countPaymentExternalInfos();
            int attemptSeqRowCount = countPaymentAttemptSeqRows();
            int lastSeq = paymentAttemptLastSeq();
            int vanApproveCount = vanGateway.approveCount();
            String storedApprovalNo = paymentAttemptApprovalNo();

            assertAll(
                    () -> assertEquals(Set.of(1), attemptSeqs, "attemptSeqs=" + attemptSeqs),
                    () -> assertEquals(1, approvalNos.size(), "approvalNos=" + approvalNos),
                    () -> assertNotNull(storedApprovalNo, "stored approvalNo must not be null"),
                    () -> assertEquals(1, paymentAttemptCount, "PAYMENT_ATTEMPT count"),
                    () -> assertEquals(1, externalInfoCount, "PAYMENT_EXTERNAL_INFO count"),
                    () -> assertEquals("APPROVED", paymentAttemptStatus(), "PAYMENT_ATTEMPT finalStatus"),
                    () -> assertEquals(storedApprovalNo, approvalNos.iterator().next(), "response approvalNo"),
                    () -> assertEquals(1, attemptSeqRowCount, "PAYMENT_ATTEMPT_SEQ row count"),
                    () -> assertEquals(1, lastSeq, "PAYMENT_ATTEMPT_SEQ LAST_SEQ"),
                    () -> assertEquals(1, vanApproveCount, "VAN approve call count")
            );
        } finally {
            executor.shutdown();
            if (executor.awaitTermination(10, TimeUnit.SECONDS) == false) {
                executor.shutdownNow();
                fail("ExecutorService did not terminate within timeout");
            }
        }
    }

    private Callable<ApproveResponse> approveTask(CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return approvalService.approve(approveRequest());
        };
    }

    private ApproveRequest approveRequest() {
        return new ApproveRequest(POS_TRX, AMOUNT, new CardInput(PAN, EXPIRY_YY_MM));
    }

    private int countPaymentAttempts() {
        return count("SELECT COUNT(*) FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", POS_TRX);
    }

    private int countPaymentExternalInfos() {
        return count("SELECT COUNT(*) FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", POS_TRX);
    }

    private String paymentAttemptStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT FINAL_STATUS FROM PAYMENT_ATTEMPT WHERE POS_TRX = ? AND ATTEMPT_SEQ = 1",
                String.class,
                POS_TRX
        );
    }

    private String paymentAttemptApprovalNo() {
        return jdbcTemplate.queryForObject(
                "SELECT APPROVAL_NO FROM PAYMENT_ATTEMPT WHERE POS_TRX = ? AND ATTEMPT_SEQ = 1",
                String.class,
                POS_TRX
        );
    }

    private int countPaymentAttemptSeqRows() {
        return count("SELECT COUNT(*) FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?", POS_TRX);
    }

    private int paymentAttemptLastSeq() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT LAST_SEQ FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?",
                Integer.class,
                POS_TRX
        ));
    }

    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, args));
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

        @Override
        public VanApproveResponse approve(VanApproveRequest request) {
            approveCount.incrementAndGet();
            return VanApproveResponse.builder()
                    .posTrx(request.posTrx())
                    .attemptSeq(request.attemptSeq())
                    .cardBin(request.cardBin())
                    .cardLast4(request.cardLast4())
                    .finalStatus(PaymentFinalStatus.APPROVED)
                    .approvalNo(APPROVAL_NO)
                    .declineCode(null)
                    .vanTrxId("VAN-TRX-CONCURRENT-APPROVE")
                    .message("OK")
                    .respondedAt(LocalDateTime.now())
                    .build();
        }

        @Override
        public com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse cancel(
                com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest request
        ) {
            throw new UnsupportedOperationException("cancel is not used in this test");
        }

        @Override
        public com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse inquiry(
                com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest request
        ) {
            throw new UnsupportedOperationException("inquiry is not used in this test");
        }

        void reset() {
            approveCount.set(0);
        }

        int approveCount() {
            return approveCount.get();
        }
    }
}
