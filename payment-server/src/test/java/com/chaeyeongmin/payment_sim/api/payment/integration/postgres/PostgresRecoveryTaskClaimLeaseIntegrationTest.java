package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/postgres-recovery-task-claim-lease-it.log"
})
class PostgresRecoveryTaskClaimLeaseIntegrationTest {

    private static final String TEST_PREFIX = "R6P5-";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 10, 0);
    private static final LocalDateTime LEASE_EXPIRES_AT = LocalDateTime.of(2026, 9, 8, 10, 5);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 8, 9, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 8, 9, 10);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_recovery_task_claim_it")
                    .withUsername("payment_sim_recovery_task_claim_it")
                    .withPassword("payment_sim_recovery_task_claim_it");

    @Autowired
    private RecoveryTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    @Test
    @DisplayName("PENDING recovery task는 claim 시 RUNNING lease로 전환된다")
    void PENDING_recovery_task는_claim_시_RUNNING_lease로_전환된다() {
        insertTask(
                "R6P5-PENDING",
                RecoveryTargetType.APPROVAL,
                1,
                RecoveryStatus.PENDING,
                0,
                null,
                null,
                null
        );

        Optional<RecoveryTask> claimed = repository.claimNext("worker-pending", NOW, LEASE_EXPIRES_AT);

        assertThat(claimed).isPresent();

        Map<String, Object> row = taskRow("R6P5-PENDING");
        assertThat(row.get("recovery_status")).isEqualTo("RUNNING");
        assertThat(row.get("claim_token")).isEqualTo("worker-pending");
        assertThat(asLocalDateTime(row.get("lease_expires_at"))).isEqualTo(LEASE_EXPIRES_AT);
        assertThat(row.get("next_retry_at")).isNull();
    }

    @Test
    @DisplayName("아직 시간이 안 된 RETRY_WAIT task는 claim되지 않는다")
    void 아직_시간이_안_된_RETRY_WAIT_task는_claim되지_않는다() {
        LocalDateTime nextRetryAt = NOW.plusMinutes(1);
        insertTask(
                "R6P5-RETRY-WAIT-FUTURE",
                RecoveryTargetType.CANCEL,
                null,
                RecoveryStatus.RETRY_WAIT,
                2,
                nextRetryAt,
                null,
                null
        );

        Optional<RecoveryTask> claimed = repository.claimNext("worker-retry-future", NOW, LEASE_EXPIRES_AT);

        assertThat(claimed).isEmpty();

        Map<String, Object> row = taskRow("R6P5-RETRY-WAIT-FUTURE");
        assertThat(row.get("recovery_status")).isEqualTo("RETRY_WAIT");
        assertThat(asLocalDateTime(row.get("next_retry_at"))).isEqualTo(nextRetryAt);
        assertThat(row.get("claim_token")).isNull();
        assertThat(row.get("lease_expires_at")).isNull();
    }

    @Test
    @DisplayName("시간이 지난 RETRY_WAIT task는 claim 시 RUNNING으로 전환된다")
    void 시간이_지난_RETRY_WAIT_task는_claim_시_RUNNING으로_전환된다() {
        LocalDateTime nextRetryAt = NOW;
        insertTask(
                "R6P5-RETRY-WAIT-DUE",
                RecoveryTargetType.CANCEL,
                null,
                RecoveryStatus.RETRY_WAIT,
                3,
                nextRetryAt,
                null,
                null
        );

        Optional<RecoveryTask> claimed = repository.claimNext("worker-retry-due", NOW, LEASE_EXPIRES_AT);

        assertThat(claimed).isPresent();

        Map<String, Object> row = taskRow("R6P5-RETRY-WAIT-DUE");
        assertThat(row.get("recovery_status")).isEqualTo("RUNNING");
        assertThat(row.get("next_retry_at")).isNull();
        assertThat(row.get("claim_token")).isEqualTo("worker-retry-due");
        assertThat(asLocalDateTime(row.get("lease_expires_at"))).isEqualTo(LEASE_EXPIRES_AT);
    }

    @Test
    @DisplayName("lease가 아직 유효한 RUNNING task는 claim되지 않고 기존 lease를 유지한다")
    void lease가_아직_유효한_RUNNING_task는_claim되지_않고_기존_lease를_유지한다() {
        LocalDateTime activeLease = NOW.plusMinutes(1);
        insertTask(
                "R6P5-RUNNING-ACTIVE",
                RecoveryTargetType.REVERSAL,
                null,
                RecoveryStatus.RUNNING,
                1,
                null,
                "worker-current",
                activeLease
        );

        Optional<RecoveryTask> claimed = repository.claimNext("worker-new", NOW, LEASE_EXPIRES_AT);

        assertThat(claimed).isEmpty();

        Map<String, Object> row = taskRow("R6P5-RUNNING-ACTIVE");
        assertThat(row.get("recovery_status")).isEqualTo("RUNNING");
        assertThat(row.get("claim_token")).isEqualTo("worker-current");
        assertThat(asLocalDateTime(row.get("lease_expires_at"))).isEqualTo(activeLease);
    }

    @Test
    @DisplayName("lease가 만료된 RUNNING task는 새로운 worker가 reclaim한다")
    void lease가_만료된_RUNNING_task는_새로운_worker가_reclaim한다() {
        LocalDateTime expiredLease = NOW;
        insertTask(
                "R6P5-RUNNING-EXPIRED",
                RecoveryTargetType.REVERSAL,
                null,
                RecoveryStatus.RUNNING,
                4,
                null,
                "worker-old",
                expiredLease
        );

        Optional<RecoveryTask> claimed = repository.claimNext("worker-new", NOW, LEASE_EXPIRES_AT);

        assertThat(claimed).isPresent();

        Map<String, Object> row = taskRow("R6P5-RUNNING-EXPIRED");
        assertThat(row.get("recovery_status")).isEqualTo("RUNNING");
        assertThat(row.get("claim_token")).isEqualTo("worker-new");
        assertThat(asLocalDateTime(row.get("lease_expires_at"))).isEqualTo(LEASE_EXPIRES_AT);
    }

    @Test
    @DisplayName("claim 결과는 RecoveryTask 도메인 모델로 매핑된다")
    void claim_결과는_RecoveryTask_도메인_모델로_매핑된다() {
        insertTask(
                "R6P5-MAPPING",
                RecoveryTargetType.APPROVAL,
                7,
                RecoveryStatus.PENDING,
                5,
                null,
                null,
                null
        );

        RecoveryTask task = repository.claimNext("worker-mapping", NOW, LEASE_EXPIRES_AT)
                .orElseThrow();

        assertThat(task.targetType()).isEqualTo(RecoveryTargetType.APPROVAL);
        assertThat(task.targetTrxNo()).isEqualTo("R6P5-MAPPING");
        assertThat(task.targetAttemptSeq()).isEqualTo(7);
        assertThat(task.originalPosTrx()).isEqualTo("R6P5-MAPPING-ORIGINAL");
        assertThat(task.originalAttemptSeq()).isEqualTo(7);
        assertThat(task.retryCount()).isEqualTo(5);
        assertThat(task.recoveryStatus()).isEqualTo(RecoveryStatus.RUNNING);
        assertThat(task.claimToken()).isEqualTo("worker-mapping");
        assertThat(task.leaseExpiresAt()).isEqualTo(LEASE_EXPIRES_AT);
        assertThat(task.nextRetryAt()).isNull();
        assertThat(task.createdAt()).isEqualTo(CREATED_AT);
        assertThat(task.updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("동시에 claim 가능한 task 1건은 worker 하나만 획득한다")
    void 동시에_claim_가능한_task_1건은_worker_하나만_획득한다() throws Exception {
        insertTask(
                "R6P5-CONCURRENT",
                RecoveryTargetType.APPROVAL,
                1,
                RecoveryStatus.PENDING,
                0,
                null,
                null,
                null
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimResult>> futures = new ArrayList<>();

        try {
            futures.add(executor.submit(claimTask("worker-a", ready, start)));
            futures.add(executor.submit(claimTask("worker-b", ready, start)));

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("Timed out while waiting for claim tasks to become ready")
                    .isTrue();

            start.countDown();

            List<ClaimResult> results = new ArrayList<>();
            List<Throwable> failures = new ArrayList<>();

            for (Future<ClaimResult> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (Exception e) {
                    failures.add(e);
                }
            }

            if (failures.isEmpty() == false) {
                AssertionError error = new AssertionError("Claim tasks failed: " + failures.size());
                failures.forEach(error::addSuppressed);
                throw error;
            }

            List<ClaimResult> winners = results.stream()
                    .filter(result -> result.task().isPresent())
                    .toList();
            List<ClaimResult> losers = results.stream()
                    .filter(result -> result.task().isEmpty())
                    .toList();

            assertThat(winners).hasSize(1);
            assertThat(losers).hasSize(1);

            String winnerToken = winners.get(0).claimToken();
            Map<String, Object> row = taskRow("R6P5-CONCURRENT");

            assertThat(row.get("recovery_status")).isEqualTo("RUNNING");
            assertThat(row.get("claim_token")).isEqualTo(winnerToken);
            assertThat(asLocalDateTime(row.get("lease_expires_at"))).isEqualTo(LEASE_EXPIRES_AT);
            assertThat(countRunningTasks("R6P5-CONCURRENT")).isEqualTo(1);
            assertThat(Set.of("worker-a", "worker-b")).contains(winnerToken);
        } finally {
            executor.shutdown();
            if (executor.awaitTermination(10, TimeUnit.SECONDS) == false) {
                executor.shutdownNow();
                fail("ExecutorService did not terminate within timeout");
            }
        }
    }

    @Test
    @DisplayName("동시에 claim 가능한 PENDING task 2건은 두 worker가 서로 다르게 획득한다")
    void 동시에_claim_가능한_PENDING_task_2건은_두_worker가_서로_다르게_획득한다() throws Exception {
        insertTask(
                "R6P5-test1",
                RecoveryTargetType.APPROVAL,
                7,
                RecoveryStatus.PENDING,
                0,
                null,
                null,
                null
        );

        insertTask(
                "R6P5-test2",
                RecoveryTargetType.APPROVAL,
                8,
                RecoveryStatus.PENDING,
                0,
                null,
                null,
                null
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<ClaimResult>> futures = new ArrayList<>();

        try {
        futures.add(executor.submit(claimTask("worker-a", ready, start)));
        futures.add(executor.submit(claimTask("worker-b", ready, start)));

        assertThat(ready.await(10, TimeUnit.SECONDS))
                .as("Timed out while waiting for claim tasks to become ready")
                .isTrue();

        start.countDown();

        ClaimResult result1 = futures.get(0).get(30, TimeUnit.SECONDS);
        ClaimResult result2 = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(result1.task()).isPresent();
        assertThat(result2.task()).isPresent();

        RecoveryTask task1 = result1.task().orElseThrow();
        RecoveryTask task2 = result2.task().orElseThrow();

        assertThat(task1.targetTrxNo()).isNotEqualTo(task2.targetTrxNo());

        assertThat(Set.of(
                task1.targetTrxNo(),
                task2.targetTrxNo()
        )).containsExactlyInAnyOrder(
                "R6P5-test1",
                "R6P5-test2"
        );

        } finally {
            executor.shutdown();
            if (executor.awaitTermination(10, TimeUnit.SECONDS) == false) {
                executor.shutdownNow();
                fail("ExecutorService did not terminate within timeout");
            }
        }

    }

    @Test
    @DisplayName("한 worker가 task를 lock 중이어도 다른 worker는 SKIP LOCKED로 다음 task를 claim한다")
    void 한_worker가_task를_lock_중이어도_다른_worker는_SKIP_LOCKED로_다음_task를_claim한다() throws Exception {
        /*
         * [준비]
         *
         * claim 가능한 PENDING Task를 2건 만든다.
         *
         * 현재 claimNext()는
         *
         * ORDER BY CREATED_AT, ID
         *
         * 이므로 CREATED_AT이 동일하다면 먼저 INSERT된 #1의 ID가 더 작고, Worker A는 #1을 먼저 가져가게 된다.
         */

        insertTask(
                "R6P5-SKIP-LOCKED-1",
                RecoveryTargetType.APPROVAL,
                7,
                RecoveryStatus.PENDING,
                0,
                null,
                null,
                null
        );

        insertTask(
                "R6P5-SKIP-LOCKED-2",
                RecoveryTargetType.APPROVAL,
                8,
                RecoveryStatus.PENDING,
                0,
                null,
                null,
                null
        );

        /*
         * Worker A와 Worker B를 별도 Thread에서 실행하기 위해
         * thread pool 크기를 2로 만든다.
         */
        ExecutorService executor = Executors.newFixedThreadPool(2);

        /*
         * workerAClaimed:
         *
         * Worker A가 claimNext()를 끝내서 #1 row를 UPDATE하고 lock을 획득했음을
         * 테스트 thread에게 알려주기 위한 latch.
         *
         * 초기값 1
         *
         * A가 workerAClaimed.countDown()
         * → 0
         * → 테스트 thread의 await() 해제
         */
        CountDownLatch workerAClaimed = new CountDownLatch(1);

        /*
         * releaseWorkerA:
         *
         * Worker A가 claim한 뒤 transaction을 바로 commit하지 않게 일부러 붙잡아두는 latch.
         *
         * 테스트 thread가 releaseWorkerA.countDown() 하기 전까지 Worker A의 transaction은 끝나지 않는다.
         *
         * 따라서 #1 row lock도 유지된다.
         */
        CountDownLatch releaseWorkerA = new CountDownLatch(1);

        try {

            /*
             * ============================================================
             * Worker A 시작
             * ============================================================
             *
             * 별도의 Transaction(REQUIRES_NEW)을 연다.
             *
             * 중요한 흐름:
             *
             * TX A BEGIN
             *      ↓
             * claimNext()
             *      ↓
             * #1 PENDING -> RUNNING
             *      ↓
             * #1 row lock 보유
             *      ↓
             * releaseWorkerA.await()
             *      ↓
             * 여기서 일부러 대기
             *
             * 즉 아직 TX A COMMIT이 발생하지 않는다.
             */

            Future<ClaimResult> workerAFuture = executor.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);

                /*
                 * Worker A는 별도의 DB Transaction을 사용한다.
                 */
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return tx.execute(status -> {
                    /*
                     * 현재 claim 가능한 가장 앞 Task(#1)를 가져간다.
                     *
                     * 내부 SQL:
                     *
                     * SELECT ... FOR UPDATE SKIP LOCKED
                     * +
                     * UPDATE ... RUNNING
                     *
                     * 여기까지 실행되면 #1 row는
                     * Worker A transaction에서 lock된 상태다.
                     */
                    Optional<RecoveryTask> task = repository.claimNext("worker-a", NOW, LEASE_EXPIRES_AT);

                    /*
                     * Main Test Thread에게:
                     *
                     * "Worker A가 claimNext()까지 끝냈다."
                     *
                     * 라고 알려준다.
                     */
                    workerAClaimed.countDown();

                    /*
                     * 여기서 Worker A를 일부러 멈춘다.
                     *
                     * 중요한 점:
                     *
                     * tx.execute(...)가 아직 return하지 않았기 때문에
                     * Transaction은 아직 COMMIT되지 않았다.
                     *
                     * 따라서 #1 row lock 역시 유지된다.
                     */
                    try {
                        releaseWorkerA.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }

                    /*
                     * releaseWorkerA가 풀리면 여기로 내려온다.
                     *
                     * 이 값을 반환하고 tx.execute()가 끝난 뒤
                     * Transaction A가 COMMIT된다.
                     */
                    return new ClaimResult("worker-a", Objects.requireNonNull(task));
                });

            });

            /*
             * ============================================================
             * Worker A가 실제 claim할 때까지 기다린다.
             * ============================================================
             *
             * 이 await가 풀렸다는 것은:
             *
             * Worker A가 claimNext() 실행을 끝냈고
             * 현재 #1 row를 잡은 transaction 안에서 대기 중이라는 뜻.
             */
            assertThat(workerAClaimed.await(10, TimeUnit.SECONDS))
                    .as("Worker A가 제한 시간 안에 task를 claim하지 못했다")
                    .isTrue();

            /*
             * 현재 DB 상황은 개념적으로:
             *
             * #1
             * RUNNING / worker-a
             * + Worker A의 DB row lock 유지 중
             *
             * #2
             * PENDING
             * + lock 없음
             */

            /*
             * ============================================================
             * Worker B 시작
             * ============================================================
             *
             * Worker A가 #1을 lock한 상태에서
             * Worker B도 claimNext()를 호출한다.
             */
            Future<ClaimResult> workerBFuture = executor.submit(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

                return tx.execute(status -> {
                    /*
                     * Worker B가 claimNext() 실행.
                     *
                     * SQL에서:
                     *
                     * #1 → Worker A가 lock 중
                     *      ↓
                     *      SKIP LOCKED
                     *
                     * #2 → lock 없음
                     *      ↓
                     *      claim
                     *
                     * 따라서 Worker B는 #1 lock이 풀릴 때까지 기다리지 않고
                     * #2를 가져와야 한다.
                     */
                    Optional<RecoveryTask> task = repository.claimNext("worker-b", NOW, LEASE_EXPIRES_AT);

                    /*
                     * Worker B는 일부러 기다리지 않는다.
                     *
                     * 바로 return
                     * → Transaction B COMMIT
                     */
                    return new ClaimResult("worker-b", Objects.requireNonNull(task));
                });

            });

            /*
             * ============================================================
             * 핵심 검증
             * ============================================================
             *
             * 아직 Worker A는 release하지 않았다.
             *
             * 즉 #1 lock은 여전히 잡혀 있다.
             *
             * 그런데 Worker B 결과를 지금 받아본다.
             *
             * SKIP LOCKED가 제대로 동작한다면
             * B는 #1을 기다리지 않고 #2를 가져오므로
             * 이 get()이 제한 시간 안에 끝나야 한다.
             */
            ClaimResult workerBResult = workerBFuture.get(5, TimeUnit.SECONDS);
            /*
             * Worker B가 실제 Task를 획득했는지 확인.
             */
            assertThat(workerBResult.task()).isPresent();
            /*
             * 그리고 Worker B가 가져간 것은 반드시 #2여야 한다.
             *
             * #1은 Worker A가 아직 lock하고 있기 때문.
             */
            RecoveryTask workerBTask = workerBResult.task().orElseThrow();
            assertThat(workerBTask.targetTrxNo()).isEqualTo("R6P5-SKIP-LOCKED-2");
            /*
             * ============================================================
             * 이제 Worker A를 풀어준다.
             * ============================================================
             *
             * 여기서 releaseWorkerA가 0이 되면서
             * Worker A의 await()가 끝난다.
             *
             * Worker A tx.execute() 종료
             * → Transaction A COMMIT
             * → #1 row lock 해제
             */
            releaseWorkerA.countDown();
            /*
             * Worker A의 최종 결과도 받아온다.
             */
            ClaimResult workerAResult = workerAFuture.get(10, TimeUnit.SECONDS);
            assertThat(workerAResult.task()).isPresent();
            RecoveryTask workerATask = workerAResult.task().orElseThrow();

            /*
             * Worker A는 #1을 가져갔어야 한다.
             */
            assertThat(workerATask.targetTrxNo()).isEqualTo("R6P5-SKIP-LOCKED-1");

            /*
             * 최종적으로:
             *
             * Worker A → #1
             * Worker B → #2
             *
             * 서로 다른 Task를 가져갔음을 한 번 더 검증.
             */
            assertThat(workerATask.targetTrxNo()).isNotEqualTo(workerBTask.targetTrxNo());
        } finally {
            /*
             * 테스트 중간 assertion이나 Future에서 예외가 발생하더라도
             *
             * Worker A가 releaseWorkerA.await()에서
             * 영원히 대기하지 않게 반드시 풀어준다.
             */
            releaseWorkerA.countDown();
            /*
             * Thread Pool 종료.
             */
            executor.shutdown();
            /*
             * Thread들이 정상 종료될 시간을 준다.
             */
            if (executor.awaitTermination(10, TimeUnit.SECONDS) == false) {
                /*
                 * 10초 안에 종료되지 않으면 강제 interrupt.
                 */
                executor.shutdownNow();
                fail("ExecutorService did not terminate within timeout");
            }

        }

    }

    private Callable<ClaimResult> claimTask(
            String claimToken,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Optional<RecoveryTask> task = transactionTemplate.execute(
                    status -> repository.claimNext(claimToken, NOW, LEASE_EXPIRES_AT)
            );
            return new ClaimResult(claimToken, Objects.requireNonNull(task));
        };
    }

    private void insertTask(
            String targetTrxNo,
            RecoveryTargetType targetType,
            Integer targetAttemptSeq,
            RecoveryStatus recoveryStatus,
            int retryCount,
            LocalDateTime nextRetryAt,
            String claimToken,
            LocalDateTime leaseExpiresAt
    ) {
        int originalAttemptSeq = targetAttemptSeq == null ? 1 : targetAttemptSeq;
        jdbcTemplate.update(
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                targetType.name(),
                targetTrxNo,
                targetAttemptSeq,
                targetTrxNo + "-ORIGINAL",
                originalAttemptSeq,
                recoveryStatus.name(),
                retryCount,
                nextRetryAt,
                claimToken,
                leaseExpiresAt,
                CREATED_AT,
                UPDATED_AT
        );
    }

    private Map<String, Object> taskRow(String targetTrxNo) {
        return jdbcTemplate.queryForMap(
                """
                SELECT
                    RECOVERY_STATUS AS recovery_status,
                    CLAIM_TOKEN AS claim_token,
                    LEASE_EXPIRES_AT AS lease_expires_at,
                    NEXT_RETRY_AT AS next_retry_at
                FROM PAYMENT_RECOVERY_TASK
                WHERE TARGET_TRX_NO = ?
                """,
                targetTrxNo
        );
    }

    private int countRunningTasks(String targetTrxNo) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM PAYMENT_RECOVERY_TASK
                WHERE TARGET_TRX_NO = ?
                  AND RECOVERY_STATUS = 'RUNNING'
                """,
                Integer.class,
                targetTrxNo
        );
        return count == null ? 0 : count;
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        throw new IllegalArgumentException("Unsupported timestamp value: " + value);
    }

    private void cleanupTestData() {
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_RECOVERY_TASK WHERE TARGET_TRX_NO LIKE ?",
                TEST_PREFIX + "%"
        );
    }

    private record ClaimResult(
            String claimToken,
            Optional<RecoveryTask> task
    ) {}

}
