package com.chaeyeongmin.van_sim.transaction;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import com.chaeyeongmin.van_sim.ledger.reversal.repository.VanReversalRepository;
import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.support.PostgresTestcontainersConfig;
import com.chaeyeongmin.van_sim.transaction.cancel.CancelService;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * 같은 APPROVED 원승인에 Cancel과 Reversal이 동시에 들어오는 경우의 cross-ledger invariant 재현 테스트다.
 *
 * <p>
 * Cancel끼리, Reversal끼리는 각각 같은 원승인 row lock으로 직렬화되지만,
 * Cancel 원장과 Reversal 원장은 서로 다른 테이블이라 한쪽 성공 후 다른 쪽 성공을 막는 검증이 필요하다.
 * 현재 테스트는 그 결함을 먼저 드러내기 위한 Phase 6-1 테스트다.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Import(PostgresTestcontainersConfig.class)
class PostgresCancelReversalConcurrencyIntegrationTest {

    private static final String ORIGINAL_POS_TRX = "A-CANCEL-REVERSAL-001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final int AMOUNT = 10_000;
    private static final String ORIGINAL_VAN_TRX_ID = "VAN-APPROVAL-CANCEL-REVERSAL-001";
    private static final String ORIGINAL_APPROVAL_NO = "APP-CAN-REV-001";
    private static final String CANCEL_POS_TRX = "C-CANCEL-REVERSAL-001";
    private static final String REVERSAL_POS_TRX = "R-CANCEL-REVERSAL-001";

    @Autowired
    private CancelService cancelService;

    @Autowired
    private ReversalService reversalService;

    @Autowired
    private VanApprovalRepository approvalRepository;

    @Autowired
    private VanCancelRepository cancelRepository;

    @Autowired
    private VanReversalRepository reversalRepository;

    @BeforeEach
    void setUp() {
        cancelRepository.deleteAll();
        reversalRepository.deleteAll();
        approvalRepository.deleteAll();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void 동일_APPROVED_원승인에_cancel과_reversal이_동시에_성공하면_안된다() throws Exception {
        // given
        // 같은 APPROVED 원승인에 대해 한 thread는 cancel, 다른 thread는 reversal을 거의 동시에 요청한다.
        // invariant는 성공 terminal 결과가 cancel/reversal 중 정확히 하나만 존재해야 한다는 것이다.
        approvalRepository.saveAndFlush(approvedOriginal());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<CancelResult> cancelFuture = executor.submit(cancelTask(ready, start));
            Future<ReversalResult> reversalFuture = executor.submit(reversalTask(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            // 여기서 CancelService.processCancel()과 ReversalService.processReversal()이 거의 동시에 진입한다.
            start.countDown();

            CancelResult cancelResult = cancelFuture.get(5, TimeUnit.SECONDS);
            ReversalResult reversalResult = reversalFuture.get(5, TimeUnit.SECONDS);

            // then
            boolean cancelSucceeded = cancelResult.cancelStatus() == VanCancelStatus.CANCELLED;
            boolean reversalSucceeded = reversalResult.reversalStatus() == VanReversalStatus.REVERSED;

            long cancelledRowCount = getCancelledRowCount();
            long reversedRowCount = getReversedRowCount();

            boolean cancelWins =
                    cancelResult.cancelStatus() == VanCancelStatus.CANCELLED
                            && cancelResult.resultCode() == CancelResultCode.SUCCESS
                            && reversalResult.reversalStatus() == VanReversalStatus.REVERSAL_DECLINED
                            && reversalResult.resultCode() == ReversalResultCode.ALREADY_CANCELLED;
            boolean reversalWins =
                    reversalResult.reversalStatus() == VanReversalStatus.REVERSED
                            && reversalResult.resultCode() == ReversalResultCode.SUCCESS
                            && cancelResult.cancelStatus() == VanCancelStatus.CANCEL_DECLINED
                            && cancelResult.resultCode() == CancelResultCode.ALREADY_REVERSED;

            assertSoftly(softly -> {
                softly.assertThat((cancelSucceeded ? 1 : 0) + (reversalSucceeded ? 1 : 0))
                        .as("cancelResult=%s, reversalResult=%s", cancelResult, reversalResult)
                        .isEqualTo(1);
                softly.assertThat(cancelledRowCount + reversedRowCount)
                        .as("van_cancel CANCELLED rows=%s, van_reversal REVERSED rows=%s",
                                cancelledRowCount,
                                reversedRowCount
                        )
                        .isEqualTo(1);
            });
            assertThat(cancelWins || reversalWins).isTrue();

            if (cancelWins) {
                assertThat(cancelResult.cancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
                assertThat(cancelResult.cancelApprovalNo()).isNotNull();
                assertThat(cancelResult.declineCode()).isNull();

                assertThat(reversalResult.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
                assertThat(reversalResult.reversalApprovalNo()).isNull();
                assertThat(reversalResult.declineCode()).isEqualTo("ALREADY_CANCELLED");

                long reversalCountBeforeReplay = reversalRepository.count();

                ReversalResult reversalReplay = reversalService.processReversal(reversalCommand());

                assertThat(reversalReplay.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
                assertThat(reversalReplay.resultCode()).isEqualTo(ReversalResultCode.ALREADY_CANCELLED);
                assertThat(reversalReplay.declineCode()).isEqualTo("ALREADY_CANCELLED");

                assertThat(reversalRepository.count()).isEqualTo(reversalCountBeforeReplay);
            }

            if (reversalWins) {
                assertThat(cancelResult.cancelStatus()).isEqualTo(VanCancelStatus.CANCEL_DECLINED);
                assertThat(cancelResult.cancelApprovalNo()).isNull();
                assertThat(cancelResult.declineCode()).isEqualTo("ALREADY_REVERSED");

                assertThat(reversalResult.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
                assertThat(reversalResult.reversalApprovalNo()).isNotNull();
                assertThat(reversalResult.declineCode()).isNull();

                long cancelCountBeforeReplay = cancelRepository.count();

                CancelResult cancelReplay = cancelService.processCancel(cancelCommand());

                assertThat(cancelReplay.cancelStatus()).isEqualTo(VanCancelStatus.CANCEL_DECLINED);
                assertThat(cancelReplay.resultCode()).isEqualTo(CancelResultCode.ALREADY_REVERSED);
                assertThat(cancelReplay.declineCode()).isEqualTo("ALREADY_REVERSED");

                assertThat(cancelRepository.count()).isEqualTo(cancelCountBeforeReplay);
            }

        } finally {
            executor.shutdownNow();
        }
    }

    private long getCancelledRowCount() {
        return cancelRepository.findAll().stream()
                .filter(cancel -> cancel.getOriginalPosTrx().equals(ORIGINAL_POS_TRX))
                .filter(cancel -> cancel.getOriginalAttemptSeq() == ORIGINAL_ATTEMPT_SEQ)
                .filter(cancel -> cancel.getCancelStatus() == VanCancelStatus.CANCELLED)
                .count();
    }

    private long getReversedRowCount() {
        return reversalRepository.findAll().stream()
                .filter(reversal -> reversal.getOriginalPosTrx().equals(ORIGINAL_POS_TRX))
                .filter(reversal -> reversal.getOriginalAttemptSeq() == ORIGINAL_ATTEMPT_SEQ)
                .filter(reversal -> reversal.getReversalStatus() == VanReversalStatus.REVERSED)
                .count();
    }

    @Test
    void cancel이_먼저_성공하면_같은_원승인의_reversal은_거절된다() {
        // given
        approvalRepository.saveAndFlush(approvedOriginal());

        // when
        CancelResult cancelResult = cancelService.processCancel(cancelCommand());
        ReversalResult reversalResult = reversalService.processReversal(reversalCommand());

        // then
        assertThat(cancelResult.cancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
        assertThat(cancelResult.resultCode()).isEqualTo(CancelResultCode.SUCCESS);

        assertThat(reversalResult.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(reversalResult.resultCode()).isEqualTo(ReversalResultCode.ALREADY_CANCELLED);

        assertThat(getCancelledRowCount()).isEqualTo(1);
        assertThat(getReversedRowCount()).isEqualTo(0);
    }

    @Test
    void reversal이_먼저_성공하면_같은_원승인의_cancel은_거절된다() {
        // given
        approvalRepository.saveAndFlush(approvedOriginal());

        // when
        ReversalResult reversalResult = reversalService.processReversal(reversalCommand());
        CancelResult cancelResult = cancelService.processCancel(cancelCommand());

        // then
        assertThat(reversalResult.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(reversalResult.resultCode()).isEqualTo(ReversalResultCode.SUCCESS);

        assertThat(cancelResult.cancelStatus()).isEqualTo(VanCancelStatus.CANCEL_DECLINED);
        assertThat(cancelResult.resultCode()).isEqualTo(CancelResultCode.ALREADY_REVERSED);

        assertThat(getReversedRowCount()).isEqualTo(1);
        assertThat(getCancelledRowCount()).isEqualTo(0);
    }

    @Test
    void 실패한_reversal은_같은_원승인의_cancel을_막지_않는다() {
        // given
        approvalRepository.saveAndFlush(approvedOriginal());

        ReversalCommand mismatchReversalCommand =
                new ReversalCommand(
                        REVERSAL_POS_TRX,
                        ORIGINAL_POS_TRX,
                        ORIGINAL_ATTEMPT_SEQ,
                        AMOUNT + 1
                );

        // when
        ReversalResult reversalResult = reversalService.processReversal(mismatchReversalCommand);
        CancelResult cancelResult = cancelService.processCancel(cancelCommand());

        // then
        assertThat(reversalResult.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(reversalResult.resultCode()).isEqualTo(ReversalResultCode.ORIGINAL_MISMATCH);

        assertThat(cancelResult.cancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
        assertThat(cancelResult.resultCode()).isEqualTo(CancelResultCode.SUCCESS);

        assertThat(getReversedRowCount()).isEqualTo(0);
        assertThat(getCancelledRowCount()).isEqualTo(1);
    }

    @Test
    void 실패한_cancel은_같은_원승인의_reversal을_막지_않는다() {
        // given
        approvalRepository.saveAndFlush(approvedOriginal());

        // when
        CancelResult cancelResult = cancelService.processCancel(mismatchCancelCommand());

        ReversalResult reversalResult = reversalService.processReversal(reversalCommand());

        // then
        assertThat(cancelResult.cancelStatus()).isEqualTo(VanCancelStatus.CANCEL_DECLINED);
        assertThat(cancelResult.resultCode()).isEqualTo(CancelResultCode.ORIGINAL_MISMATCH);

        assertThat(reversalResult.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(reversalResult.resultCode()).isEqualTo(ReversalResultCode.SUCCESS);

        assertThat(getCancelledRowCount()).isEqualTo(0);
        assertThat(getReversedRowCount()).isEqualTo(1);
    }

    private Callable<CancelResult> cancelTask(
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return cancelService.processCancel(cancelCommand());
        };
    }

    private Callable<ReversalResult> reversalTask(
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return reversalService.processReversal(reversalCommand());
        };
    }

    private CancelCommand cancelCommand() {
        return new CancelCommand(
                CANCEL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                ORIGINAL_VAN_TRX_ID,
                ORIGINAL_APPROVAL_NO,
                AMOUNT
        );
    }

    private ReversalCommand reversalCommand() {
        return new ReversalCommand(
                REVERSAL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                AMOUNT
        );
    }

    private VanApproval approvedOriginal() {
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
                .processedAt(LocalDateTime.of(2026, 9, 4, 10, 0))
                .build();
    }

    private CancelCommand mismatchCancelCommand() {
        return new CancelCommand(
                CANCEL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                ORIGINAL_VAN_TRX_ID,
                "WRONG-APPROVAL-NO",
                AMOUNT
        );
    }

}
