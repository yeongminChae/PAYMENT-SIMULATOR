package com.chaeyeongmin.van_sim.transaction.reversal.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import com.chaeyeongmin.van_sim.ledger.reversal.repository.VanReversalRepository;
import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import com.chaeyeongmin.van_sim.transaction.reversal.ReversalService;
import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 원승인에 대한 동시 reversal 요청의 PostgreSQL row lock 수렴 테스트 보일러플레이트.
 *
 * <p>
 * 동시성 검증은 실제 Spring Bean의 @Transactional 메서드를 별도 thread에서 호출한다.
 * 테스트 메서드 자체에는 transaction을 걸지 않아 운영 흐름과 같은 commit 경계를 사용한다.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class PostgresReversalConcurrencyIntegrationTest {

    private static final String ORIGINAL_POS_TRX = "A-REVERSAL-001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final int AMOUNT = 10_000;
    private static final String REVERSAL_POS_TRX_1 = "R-REVERSAL-001";
    private static final String REVERSAL_POS_TRX_2 = "R-REVERSAL-002";

    @Autowired
    private ReversalService reversalService;

    @Autowired
    private VanApprovalRepository approvalRepository;

    @Autowired
    private VanReversalRepository reversalRepository;

    @BeforeEach
    void setUp() {
        reversalRepository.deleteAll();
        approvalRepository.deleteAll();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void 동일_원승인에_서로_다른_reversalPosTrx_동시_reversal은_row_1건으로_수렴한다() throws Exception {
        // 같은 원승인에 대한 두 reversal 요청이 거의 동시에 들어오는 상황이다.
        // 한 요청만 신규 REVERSED owner가 되고, 다른 요청은 기존 row를 보고 ALREADY_REVERSED로 수렴해야 한다.
        approvalRepository.saveAndFlush(approvedOriginal());

        ReversalCommand command1 = command(REVERSAL_POS_TRX_1);
        ReversalCommand command2 = command(REVERSAL_POS_TRX_2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<ReversalResult> future1 = executor.submit(reversalTask(command1, ready, start));
            Future<ReversalResult> future2 = executor.submit(reversalTask(command2, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ReversalResult result1 = future1.get(5, TimeUnit.SECONDS);
            ReversalResult result2 = future2.get(5, TimeUnit.SECONDS);
            List<ReversalResult> results = List.of(result1, result2);

            assertThat(results)
                    .extracting(ReversalResult::resultCode)
                    .containsExactlyInAnyOrder(
                            ReversalResultCode.SUCCESS,
                            ReversalResultCode.ALREADY_REVERSED
                    );
            assertThat(results)
                    .extracting(ReversalResult::reversalStatus)
                    .containsOnly(VanReversalStatus.REVERSED);
            assertThat(results)
                    .extracting(ReversalResult::reversalPosTrx)
                    .containsExactlyInAnyOrder(REVERSAL_POS_TRX_1, REVERSAL_POS_TRX_2);

            // 결과 코드만 맞고 row가 두 개 생기면 원장 불변식이 깨진 것이다.
            assertThat(reversalRepository.count()).isEqualTo(1);

            Optional<VanReversal> stored =
                    reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                            ORIGINAL_POS_TRX,
                            ORIGINAL_ATTEMPT_SEQ
                    );
            assertThat(stored).isPresent();
            assertThat(stored.get().getReversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
            assertThat(stored.get().getReversalPosTrx())
                    .isIn(Set.of(REVERSAL_POS_TRX_1, REVERSAL_POS_TRX_2));

            VanApproval original = approvalRepository.findByPosTrxAndAttemptSeq(
                    ORIGINAL_POS_TRX,
                    ORIGINAL_ATTEMPT_SEQ
            ).orElseThrow();
            // Reversal은 승인 원장을 종료시키는 거래가 아니라 별도 복구 사실이다.
            assertThat(original.getApprovalStatus()).isEqualTo(VanApprovalStatus.APPROVED);
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<ReversalResult> reversalTask(
            ReversalCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return reversalService.processReversal(command);
        };
    }

    private ReversalCommand command(String reversalPosTrx) {
        return new ReversalCommand(
                reversalPosTrx,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                AMOUNT
        );
    }

    private VanApproval approvedOriginal() {
        return VanApproval.builder()
                .vanTrxId("VAN-APPROVAL-REVERSAL-001")
                .posTrx(ORIGINAL_POS_TRX)
                .attemptSeq(ORIGINAL_ATTEMPT_SEQ)
                .amount(AMOUNT)
                .cardBin("12345678")
                .cardLast4("1234")
                .approvalStatus(VanApprovalStatus.APPROVED)
                .approvalNo("APP-REV-001")
                .declineCode(null)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
