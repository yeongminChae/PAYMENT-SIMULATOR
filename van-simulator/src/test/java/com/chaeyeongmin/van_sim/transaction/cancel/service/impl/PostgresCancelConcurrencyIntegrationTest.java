package com.chaeyeongmin.van_sim.transaction.cancel.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import com.chaeyeongmin.van_sim.transaction.cancel.CancelService;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class PostgresCancelConcurrencyIntegrationTest {

    // 같은 원승인(A001/1)에 대해 서로 다른 취소 거래번호(C001, C002)가 동시에 들어오는 상황을 만든다.
    // 테스트의 관심사는 van_cancel unique constraint가 아니라 van_approval row lock으로 요청이 직렬화되는지다.
    private static final String ORIGINAL_POS_TRX = "A001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final int AMOUNT = 10_000;
    private static final String ORIGINAL_VAN_TRX_ID = "VAN-APPROVAL-001";
    private static final String ORIGINAL_APPROVAL_NO = "APPROVAL-001";
    private static final String CANCEL_POS_TRX_1 = "C001";
    private static final String CANCEL_POS_TRX_2 = "C002";

    @Autowired
    private CancelService cancelService;

    @Autowired
    private VanApprovalRepository approvalRepository;

    @Autowired
    private VanCancelRepository cancelRepository;

    @BeforeEach
    void setUp() {
        // schema-postgres.sql은 Testcontainers PostgreSQL에 매번 적용된다.
        // 테스트 간 원장 데이터만 비워서 같은 fixture 키를 안정적으로 재사용한다.
        cancelRepository.deleteAll();
        approvalRepository.deleteAll();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void 동일_원승인에_서로_다른_cancelPosTrx_동시_취소는_PostgreSQL_row_lock으로_직렬화된다() throws Exception {
        // given
        // 원승인은 이미 APPROVED 상태로 존재한다.
        // CancelServiceImpl.processCancel()은 이 row를 findByPosTrxAndAttemptSeqForUpdate()로 조회하며
        // PostgreSQL에서는 해당 조회가 SELECT ... FOR UPDATE row lock으로 동작해야 한다.
        approvalRepository.saveAndFlush(approvedOriginalApproval());

        // 두 요청은 originalPosTrx/originalAttemptSeq/originalVanTrxId/originalApprovalNo/amount가 모두 같고
        // cancelPosTrx만 다르다. 따라서 정상 직렬화되면 한쪽은 신규 취소, 다른 한쪽은 기존 취소 재응답이다.
        CancelCommand command1 = cancelCommand(CANCEL_POS_TRX_1);
        CancelCommand command2 = cancelCommand(CANCEL_POS_TRX_2);

        // 두 worker가 별도 thread에서 실제 Spring Bean의 @Transactional 메서드를 호출하게 한다.
        // 테스트 메서드 자체에는 @Transactional을 붙이지 않아 worker별 트랜잭션 경계를 실제 운영 흐름과 맞춘다.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<CancelResult> future1 = executor.submit(cancelTask(command1, ready, start));
            Future<CancelResult> future2 = executor.submit(cancelTask(command2, ready, start));

            // 두 worker가 start latch 앞까지 도착했음을 확인한 뒤 동시에 풀어준다.
            // Thread.sleep()으로 순서를 기대하지 않고, 시작 시점만 최대한 맞춘다.
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            // 여기서 C001/C002가 거의 동시에 CancelServiceImpl.processCancel()로 진입한다.
            // 기대 흐름:
            // 1) 한 thread가 van_approval row lock을 잡고 van_cancel CANCELLED row를 저장한 뒤 commit
            // 2) 다른 thread는 같은 row lock에서 대기
            // 3) commit 이후 lock을 얻고 기존 van_cancel을 재조회해 ALREADY_CANCELLED 반환
            start.countDown();

            // Future#get에서 예외가 터지면 테스트가 실패한다.
            // 이 검증이 중요하다. unique violation으로 한 요청이 실패한 경우는 직렬화 성공이 아니다.
            CancelResult result1 = future1.get(5, TimeUnit.SECONDS);
            CancelResult result2 = future2.get(5, TimeUnit.SECONDS);
            List<CancelResult> results = List.of(result1, result2);

            // then
            // 두 요청 모두 정상 응답해야 한다. 하나라도 예외면 Future#get에서 이미 실패한다.
            assertThat(results).hasSize(2);
            // 신규 취소를 만든 요청은 SUCCESS, lock 대기 후 기존 취소를 발견한 요청은 ALREADY_CANCELLED다.
            // thread scheduling은 비결정적이므로 어느 cancelPosTrx가 SUCCESS인지는 고정하지 않는다.
            assertThat(results)
                    .extracting(CancelResult::resultCode)
                    .containsExactlyInAnyOrder(
                            CancelResultCode.SUCCESS,
                            CancelResultCode.ALREADY_CANCELLED
                    );
            // ALREADY_CANCELLED도 "이미 취소 완료된 원승인"을 반환하는 성공 계열 응답이므로 상태는 CANCELLED다.
            assertThat(results)
                    .extracting(CancelResult::cancelStatus)
                    .containsOnly(VanCancelStatus.CANCELLED);
            // alreadyProcessedResult()는 실제 저장 owner의 cancelPosTrx가 아니라 현재 요청의 cancelPosTrx로
            // correlation을 유지해야 한다. 그래서 응답에는 C001/C002가 각각 살아 있어야 한다.
            assertThat(results)
                    .extracting(CancelResult::cancelPosTrx)
                    .containsExactlyInAnyOrder(CANCEL_POS_TRX_1, CANCEL_POS_TRX_2);

            Map<String, CancelResult> resultByCancelPosTrx = results.stream()
                    .collect(Collectors.toMap(CancelResult::cancelPosTrx, Function.identity()));
            assertThat(resultByCancelPosTrx.get(CANCEL_POS_TRX_1).originalPosTrx())
                    .isEqualTo(ORIGINAL_POS_TRX);
            assertThat(resultByCancelPosTrx.get(CANCEL_POS_TRX_2).originalPosTrx())
                    .isEqualTo(ORIGINAL_POS_TRX);

            // DB row 수만 보면 unique(original_pos_trx, original_attempt_seq) 때문에도 1건이 될 수 있다.
            // 위에서 SUCCESS/ALREADY_CANCELLED/예외 없음까지 먼저 검증한 뒤 보조적으로 원장 개수를 확인한다.
            assertThat(cancelRepository.count()).isEqualTo(1);

            // 같은 원승인에 대해 실제 저장된 취소 원장은 정확히 한 건이어야 한다.
            // 저장 owner는 C001/C002 중 먼저 lock을 잡은 쪽이며 스케줄링에 따라 달라질 수 있다.
            Optional<VanCancel> storedCancelOptional =
                    cancelRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                            ORIGINAL_POS_TRX,
                            ORIGINAL_ATTEMPT_SEQ
                    );
            assertThat(storedCancelOptional).isPresent();

            VanCancel storedCancel = storedCancelOptional.get();
            assertThat(storedCancel.getCancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
            assertThat(storedCancel.getCancelApprovalNo()).isNotNull();
            assertThat(storedCancel.getDeclineCode()).isNull();
            assertThat(storedCancel.getCancelPosTrx())
                    .isIn(Set.of(CANCEL_POS_TRX_1, CANCEL_POS_TRX_2));

            // Cancel은 원승인 row를 변경하지 않고 별도 van_cancel 원장에만 기록한다.
            // 따라서 lock 대상이었던 van_approval row의 승인 상태는 APPROVED 그대로 남아야 한다.
            VanApproval originalApproval =
                    approvalRepository.findByPosTrxAndAttemptSeq(
                                    ORIGINAL_POS_TRX,
                                    ORIGINAL_ATTEMPT_SEQ
                            )
                            .orElseThrow();
            assertThat(originalApproval.getApprovalStatus()).isEqualTo(VanApprovalStatus.APPROVED);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 각 worker가 호출하는 동시 취소 작업이다.
     *
     * <p>
     * ready latch는 "thread가 준비됐음"을 테스트 메서드에 알리고,
     * start latch는 두 thread가 같은 시점에 processCancel()로 진입하게 맞춘다.
     * 실제 검증 대상 메서드는 {@link CancelService#processCancel(CancelCommand)}이다.
     */
    private Callable<CancelResult> cancelTask(
            CancelCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return cancelService.processCancel(command);
        };
    }

    /**
     * 실제 취소 요청 DTO를 만든다.
     *
     * <p>
     * cancelPosTrx만 인자로 받고 원승인 식별자와 금액은 fixture의 APPROVED 원승인 row와 동일하게 고정한다.
     * 그래야 두 요청의 차이가 "취소 거래번호만 다름"으로 제한된다.
     */
    private CancelCommand cancelCommand(String cancelPosTrx) {
        return new CancelCommand(
                cancelPosTrx,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                ORIGINAL_VAN_TRX_ID,
                ORIGINAL_APPROVAL_NO,
                AMOUNT
        );
    }

    /**
     * 취소 대상이 되는 원승인 fixture다.
     *
     * <p>
     * CancelServiceImpl은 이 row를 pessimistic write lock으로 조회한 뒤 상태와 payload를 검증한다.
     * 상태가 APPROVED이고 요청 payload와 일치해야 신규 CANCELLED 원장을 저장할 수 있다.
     */
    private VanApproval approvedOriginalApproval() {
        return VanApproval.builder()
                .vanTrxId(ORIGINAL_VAN_TRX_ID)
                .posTrx(ORIGINAL_POS_TRX)
                .attemptSeq(ORIGINAL_ATTEMPT_SEQ)
                .amount(AMOUNT)
                .cardBin("12345678")
                .cardLast4("1234")
                .approvalStatus(VanApprovalStatus.APPROVED)
                .approvalNo(ORIGINAL_APPROVAL_NO)
                .declineCode(null)
                .processedAt(LocalDateTime.of(2026, 8, 31, 10, 0))
                .build();
    }
}
