package com.chaeyeongmin.payment_sim.api.payment.integration;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
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
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PostgreSQL 동일 원거래 취소 동시성 멱등성 테스트.
 *
 * <p>HTTP 계약보다 취소 유스케이스의 핵심 정합성에 집중하기 위해 Service를 직접 호출한다.
 * 테스트 전용 {@link CountingVanGateway}는 Spring {@code @Primary} Bean으로 실제 {@link VanGateway}를 대체하며,
 * AtomicInteger로 approve/cancel 호출 횟수를 thread-safe하게 관찰한다.
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
        "logging.file.name=./build/logs/postgres-cancel-concurrency-it.log"
})
class PostgresCancelConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 20;
    private static final String ORIGINAL_POS_TRX = "2376-20260806-9911-3301";
    private static final String CANCEL_POS_TRX_PREFIX = "2376-20260806-9911-43";
    private static final int AMOUNT = 10000;
    private static final String PAN = "4242424242424242";
    private static final String EXPIRY_YY_MM = "2812";
    private static final String APPROVAL_NO = "A888000001";
    private static final String CANCEL_APPROVAL_NO = "C888000001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_cancel_concurrency_it")
                    .withUsername("payment_sim_cancel_concurrency_it")
                    .withPassword("payment_sim_cancel_concurrency_it");

    @Autowired
    private PaymentApprovalService approvalService;

    @Autowired
    private PaymentCancelService cancelService;

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
     * - Given: PostgreSQL Testcontainers DB에 정상 승인된 원거래 PAYMENT_ATTEMPT 1건이 있다.
     * - And  : 원거래는 같은 카드/금액으로 APPROVED 되었고, 아직 PAYMENT_CANCEL row는 없다.
     * - When : 같은 originalPosTrx + originalAttemptSeq를 대상으로 cancel posTrx만 모두 다른 취소 요청 20개를 동시에 실행한다.
     * - Then : 전체 작업 결과 20개가 모두 수집되고, 예상하지 않은 예외는 없어야 한다.
     * - And  : 실제 신규 취소 성공 응답은 1건만 있어야 한다.
     * - And  : 나머지 19건은 같은 원거래 취소 결과를 재사용하는 ALREADY_CANCELLED 응답이어야 한다.
     * - And  : 모든 응답은 같은 실제 cancelApprovalNo를 가리켜야 한다.
     *
     * [DB/VAN 불변조건]
     * - VAN cancel 호출은 정확히 1회여야 한다.
     * - PAYMENT_CANCEL row는 originalPosTrx + originalAttemptSeq 기준 정확히 1건이어야 한다.
     * - 저장된 PAYMENT_CANCEL.originalPosTrx는 승인 원거래와 일치해야 한다.
     * - 서로 다른 후속 cancel posTrx들은 별도 PAYMENT_CANCEL row를 만들면 안 된다.
     * - 저장된 cancelApprovalNo는 응답의 cancelApprovalNo와 일치해야 한다.
     * - 원승인 PAYMENT_ATTEMPT의 approvalNo는 취소 후에도 바뀌면 안 된다.
     * - 현재 기대 정책상 원승인 PAYMENT_ATTEMPT 최종 상태는 APPROVED로 관찰되어야 한다.
     * - 중복 취소 재응답은 CANCEL_REUSED_BY_ORIGINAL 이벤트로 추적되어야 한다.
     *
     * [동시 실행 구조]
     * - ExecutorService 고정 스레드 풀로 요청 수만큼 작업을 제출한다.
     * - ready latch로 모든 작업이 시작선에 도착했음을 확인한 뒤 start latch를 열어 동시에 실행한다.
     * - Future 결과와 예외를 모두 수집하며, timeout/예외를 무시하지 않는다.
     * - executor는 finally에서 종료하고 제한 시간 내 종료되지 않으면 테스트를 실패시킨다.
     *
     * [검증 의도]
     * - 같은 cancel posTrx 반복 멱등성이 아니라, 서로 다른 취소 거래번호가 같은 원승인을 동시에 취소할 때의
     *   원거래 기준 직렬화/중복 방어를 검증한다.
     * - DB row 수가 1건으로 남더라도 VAN cancel이 여러 번 호출되면 실패로 본다.
     */
    @Test
    @DisplayName("PostgreSQL 동일 원거래에 서로 다른 취소 거래번호가 동시에 들어와도 VAN 취소와 cancel row는 1회다")
    void cancelSameOriginalWithDifferentCancelPosTrxConcurrently_shouldCancelOnlyOnce() throws Exception {
        ApproveResponse approve = approvalService.approve(approveRequest());
        assertEquals(PaymentFinalStatus.APPROVED, approve.finalStatus());
        assertEquals(APPROVAL_NO, approve.approvalNo());

        List<String> cancelPosTrxs = IntStream.rangeClosed(1, REQUEST_COUNT)
                .mapToObj(i -> CANCEL_POS_TRX_PREFIX + "%02d".formatted(i))
                .toList();

        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CancelResponse>> futures = new ArrayList<>();

        try {
            for (String cancelPosTrx : cancelPosTrxs) {
                futures.add(executor.submit(cancelTask(ready, start, cancelPosTrx, approve.attemptSeq())));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Timed out while waiting for all cancel tasks to become ready"
            );

            start.countDown();

            List<CancelResponse> responses = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<CancelResponse> future : futures) {
                try {
                    responses.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failures.add(e);
                }
            }

            long retryLaterCount = countByStatus(responses, CancelResultStatus.RETRY_LATER);
            long cancelledCount = countByStatus(responses, CancelResultStatus.CANCELLED);
            long alreadyCancelledCount = countByStatus(responses, CancelResultStatus.ALREADY_CANCELLED);
            boolean allResponsesHaveCancelApprovalNo = responses.stream()
                    .allMatch(response -> response.cancelApprovalNo() != null);
            Set<String> responseCancelApprovalNos = responses.stream()
                    .map(CancelResponse::cancelApprovalNo)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            String storedCancelPosTrx = currentCancelPosTrx(ORIGINAL_POS_TRX, approve.attemptSeq());

            assertAll(
                    () -> assertEquals(REQUEST_COUNT, responses.size(), "collected cancel results"),
                    () -> assertEquals(0, failures.size(), () -> failureMessage(failures)),
                    () -> assertEquals(1, cancelledCount, "CANCELLED response count"),
                    () -> assertEquals(0, retryLaterCount, "RETRY_LATER response count"),
                    () -> assertEquals(REQUEST_COUNT - 1, alreadyCancelledCount, "ALREADY_CANCELLED response count"),
                    () -> assertTrue(allResponsesHaveCancelApprovalNo, "all responses must have cancelApprovalNo"),
                    () -> assertEquals(Set.of(CANCEL_APPROVAL_NO), responseCancelApprovalNos, "response cancelApprovalNos"),
                    () -> assertEquals(1, vanGateway.cancelCount(), "VAN cancel call count"),
                    () -> assertEquals(1, countPaymentCancelByOriginal(ORIGINAL_POS_TRX, approve.attemptSeq()), "PAYMENT_CANCEL original row count"),
                    () -> assertEquals(ORIGINAL_POS_TRX, paymentCancelOriginalPosTrx(storedCancelPosTrx), "PAYMENT_CANCEL originalPosTrx"),
                    () -> assertEquals("CANCELLED", paymentCancelStatus(storedCancelPosTrx), "PAYMENT_CANCEL status"),
                    () -> assertEquals(CANCEL_APPROVAL_NO, paymentCancelApprovalNo(storedCancelPosTrx), "stored cancelApprovalNo"),
                    () -> assertEquals(0, countPaymentCancelRowsExcept(storedCancelPosTrx), "extra PAYMENT_CANCEL rows"),
                    () -> assertTrue(cancelPosTrxs.contains(storedCancelPosTrx), "stored cancel posTrx must be one request posTrx"),
                    () -> assertEquals("APPROVED", paymentAttemptStatus(ORIGINAL_POS_TRX, approve.attemptSeq()), "original PAYMENT_ATTEMPT finalStatus"),
                    () -> assertEquals(APPROVAL_NO, paymentAttemptApprovalNo(ORIGINAL_POS_TRX, approve.attemptSeq()), "original approvalNo"),
                    () -> assertEquals(
                            REQUEST_COUNT - 1,
                            countCancelReusedByOriginalEvents(ORIGINAL_POS_TRX, approve.attemptSeq()),
                            "CANCEL_REUSED_BY_ORIGINAL event count"
                    )
            );
        } finally {
            executor.shutdown();
            if (executor.awaitTermination(10, TimeUnit.SECONDS) == false) {
                executor.shutdownNow();
                fail("ExecutorService did not terminate within timeout");
            }
        }
    }

    private Callable<CancelResponse> cancelTask(
            CountDownLatch ready,
            CountDownLatch start,
            String cancelPosTrx,
            int originalAttemptSeq
    ) {
        return () -> {
            ready.countDown();
            start.await();
            return cancelService.cancel(cancelRequest(cancelPosTrx, originalAttemptSeq));
        };
    }

    private ApproveRequest approveRequest() {
        return new ApproveRequest(ORIGINAL_POS_TRX, AMOUNT, new CardInput(PAN, EXPIRY_YY_MM));
    }

    private CancelRequest cancelRequest(String cancelPosTrx, int originalAttemptSeq) {
        return new CancelRequest(cancelPosTrx, ORIGINAL_POS_TRX, originalAttemptSeq, PAN);
    }

    private long countByStatus(List<CancelResponse> responses, CancelResultStatus status) {
        return responses.stream()
                .filter(response -> response.cancelStatus() == status)
                .count();
    }

    private String failureMessage(List<Throwable> failures) {
        StringBuilder builder = new StringBuilder("Unexpected cancel task failures: ");
        builder.append(failures.size());
        failures.forEach(failure -> builder.append(System.lineSeparator()).append(failure));
        return builder.toString();
    }

    private int countPaymentCancelByOriginal(String originalPosTrx, int originalAttemptSeq) {
        return count(
                """
                SELECT COUNT(*)
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                originalPosTrx,
                originalAttemptSeq
        );
    }

    private int countPaymentCancelRowsExcept(String currentPosTrx) {
        return count(
                """
                SELECT COUNT(*)
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND CURRENT_TRX_NO <> ?
                """,
                ORIGINAL_POS_TRX,
                currentPosTrx
        );
    }

    private String currentCancelPosTrx(String originalPosTrx, int originalAttemptSeq) {
        return jdbcTemplate.queryForObject(
                """
                SELECT CURRENT_TRX_NO
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                String.class,
                originalPosTrx,
                originalAttemptSeq
        );
    }

    private String paymentCancelOriginalPosTrx(String currentPosTrx) {
        return jdbcTemplate.queryForObject(
                "SELECT ORIGINAL_TRX_NO FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO = ?",
                String.class,
                currentPosTrx
        );
    }

    private String paymentCancelStatus(String currentPosTrx) {
        return jdbcTemplate.queryForObject(
                "SELECT CANCEL_STATUS FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO = ?",
                String.class,
                currentPosTrx
        );
    }

    private String paymentCancelApprovalNo(String currentPosTrx) {
        return jdbcTemplate.queryForObject(
                "SELECT CANCEL_APPROVAL_NO FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO = ?",
                String.class,
                currentPosTrx
        );
    }

    private String paymentAttemptStatus(String posTrx, int attemptSeq) {
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

    private String paymentAttemptApprovalNo(String posTrx, int attemptSeq) {
        return jdbcTemplate.queryForObject(
                """
                SELECT APPROVAL_NO
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                String.class,
                posTrx,
                attemptSeq
        );
    }

    private int countCancelReusedByOriginalEvents(String originalPosTrx, int originalAttemptSeq) {
        return count(
                """
                SELECT COUNT(*)
                FROM PAYMENT_EVENT_LOG
                WHERE EVENT_TYPE = ?
                  AND ORIGINAL_POS_TRX = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                PaymentEventType.CANCEL_REUSED_BY_ORIGINAL.name(),
                originalPosTrx,
                originalAttemptSeq
        );
    }

    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, args));
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                """
                DELETE FROM PAYMENT_EVENT_LOG
                WHERE POS_TRX = ?
                   OR POS_TRX LIKE ?
                   OR ORIGINAL_POS_TRX = ?
                """,
                ORIGINAL_POS_TRX,
                CANCEL_POS_TRX_PREFIX + "%",
                ORIGINAL_POS_TRX
        );
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

        private final AtomicInteger approveCount = new AtomicInteger();
        private final AtomicInteger cancelCount = new AtomicInteger();

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
                    .vanTrxId("VAN-TRX-CANCEL-CONCURRENCY-APPROVE")
                    .message("OK")
                    .respondedAt(LocalDateTime.now())
                    .build();
        }

        @Override
        public VanCancelResponse cancel(VanCancelRequest request) {
            cancelCount.incrementAndGet();
            return VanCancelResponse.builder()
                    .posTrx(request.posTrx())
                    .originalPosTrx(request.originalPosTrx())
                    .originalAttemptSeq(request.originalAttemptSeq())
                    .cancelStatus(CancelStatus.CANCELLED)
                    .cancelApprovalNo(CANCEL_APPROVAL_NO)
                    .declineCode(null)
                    .vanTrxId("VAN-TRX-CANCEL-CONCURRENCY-CANCEL")
                    .message("CANCELLED")
                    .respondedAt(LocalDateTime.now())
                    .build();
        }

        @Override
        public VanInquiryResponse inquiry(VanInquiryRequest request) {
            throw new UnsupportedOperationException("inquiry is not used in this test");
        }

        void reset() {
            approveCount.set(0);
            cancelCount.set(0);
        }

        int cancelCount() {
            return cancelCount.get();
        }
    }
}
