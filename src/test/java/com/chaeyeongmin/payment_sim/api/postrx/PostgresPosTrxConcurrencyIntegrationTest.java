package com.chaeyeongmin.payment_sim.api.postrx;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueResponse;
import com.chaeyeongmin.payment_sim.api.postrx.service.PosTrxService;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PostgreSQL POS 거래번호 동시 발급 정합성 테스트.
 *
 * <p>이 테스트는 HTTP 계약보다 DB 채번 정합성에 초점을 둔다. 따라서 Controller/MockMvc 대신
 * {@link PosTrxService}를 직접 호출해 Service -> Repository -> MyBatis -> PostgreSQL 경로를 검증한다.
 * MockMvc를 여러 스레드에서 동시에 호출하는 것보다 테스트 변수가 적고, 검증하려는 대상인
 * {@code POS_TRX_SEQUENCE} UPSERT 원자성을 더 직접적으로 확인할 수 있다.
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
        "logging.file.name=./build/logs/postgres-pos-trx-concurrency-it.log"
})
class PostgresPosTrxConcurrencyIntegrationTest {

    private static final int REQUEST_COUNT = 20;
    private static final String STORE_CD = "2376";
    private static final String BIZ_DATE = "20260806";
    private static final String POS_NO = "7721";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_concurrency_it")
                    .withUsername("payment_sim_concurrency_it")
                    .withPassword("payment_sim_concurrency_it");

    @Autowired
    private PosTrxService posTrxService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBefore() {
        cleanupTestData();
    }

    @AfterEach
    void cleanAfter() {
        cleanupTestData();
    }

    /**
     * [시나리오]
     * - Given: PostgreSQL POS_TRX_SEQUENCE에 테스트 복합키 row가 없는 상태다.
     * - When : 동일 storeCd/bizDate/posNo로 POS 거래번호 발급을 20개 스레드에서 동시에 요청한다.
     * - Then : 모든 요청은 성공하고, 반환된 posTrx는 20개이며 모두 중복 없이 유일하다.
     * - And  : 마지막 4자리 순번 집합은 0001부터 0020까지 정확히 한 번씩 존재한다.
     * - And  : DB에는 해당 복합키 row가 1건만 있고 최종 SEQ는 20이다.
     *
     * [검증 의도]
     * - PostgreSQL UPSERT가 동시 충돌 상황에서도 순번 누락/중복 없이 원자적으로 증가하는지 확인한다.
     * - 테스트는 성능 측정이 아니라 정합성 검증이며, 실행 시간은 성공 기준으로 사용하지 않는다.
     */
    @Test
    @DisplayName("PostgreSQL POS 거래번호 동시 발급은 중복과 누락 없이 1부터 20까지 발급된다")
    void issuePosTrxConcurrently_shouldIssueUniqueSequentialNumbers() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
        CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < REQUEST_COUNT; i++) {
                futures.add(executor.submit(issueTask(ready, start)));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Timed out while waiting for all POS issue tasks to become ready"
            );

            start.countDown();

            List<String> posTrxs = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    posTrxs.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failures.add(e);
                }
            }

            if (failures.isEmpty() == false) {
                AssertionError error = new AssertionError("POS issue tasks failed: " + failures.size());
                failures.forEach(error::addSuppressed);
                throw error;
            }

            List<String> actualSequences = posTrxs.stream()
                    .map(posTrx -> posTrx.substring(posTrx.length() - 4))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            List<String> expectedSequences = IntStream.rangeClosed(1, REQUEST_COUNT)
                    .mapToObj(seq -> "%04d".formatted(seq))
                    .toList();
            Set<String> uniquePosTrxs = Set.copyOf(posTrxs);

            assertEquals(REQUEST_COUNT, posTrxs.size());
            assertEquals(REQUEST_COUNT, uniquePosTrxs.size());
            assertEquals(expectedSequences, actualSequences);
            assertEquals(1, countPosTrxSequence());
            assertEquals(REQUEST_COUNT, posTrxSequence());
        } finally {
            executor.shutdown();
            if (executor.awaitTermination(10, TimeUnit.SECONDS) == false) {
                executor.shutdownNow();
                fail("ExecutorService did not terminate within timeout");
            }
        }
    }

    private Callable<String> issueTask(CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();

            PosTrxIssueResponse response =
                    posTrxService.issue(new PosTrxIssueRequest(STORE_CD, BIZ_DATE, POS_NO));

            return response.getPos_trx();
        };
    }

    private int countPosTrxSequence() {
        return count(
                """
                SELECT COUNT(*)
                FROM POS_TRX_SEQUENCE
                WHERE STORE_CD = ?
                  AND BIZ_DATE = ?
                  AND POS_NO = ?
                """,
                STORE_CD,
                BIZ_DATE,
                POS_NO
        );
    }

    private int posTrxSequence() {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                """
                SELECT SEQ
                FROM POS_TRX_SEQUENCE
                WHERE STORE_CD = ?
                  AND BIZ_DATE = ?
                  AND POS_NO = ?
                """,
                Integer.class,
                STORE_CD,
                BIZ_DATE,
                POS_NO
        ));
    }

    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, args));
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                """
                DELETE FROM POS_TRX_SEQUENCE
                WHERE STORE_CD = ?
                  AND BIZ_DATE = ?
                  AND POS_NO = ?
                """,
                STORE_CD,
                BIZ_DATE,
                POS_NO
        );
    }
}
