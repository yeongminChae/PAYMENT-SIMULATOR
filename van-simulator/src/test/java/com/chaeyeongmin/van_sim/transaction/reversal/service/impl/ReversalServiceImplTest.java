package com.chaeyeongmin.van_sim.transaction.reversal.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import com.chaeyeongmin.van_sim.ledger.reversal.repository.VanReversalRepository;
import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.exception.ReversalRequestConflictException;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReversalServiceImpl business 계약 테스트.
 *
 * <p>
 * Reversal은 승인번호/VAN 승인 거래번호 없이 원승인 식별자와 금액만으로 복구 가능성을 판단한다.
 * UNKNOWN 원승인도 reversal 가능해야 하므로 CancelServiceImpl의 검증 규칙을 그대로 복사하면 안 된다.
 */
class ReversalServiceImplTest {

    private static final String REVERSAL_POS_TRX = "2301-20260808-9999-R001";
    private static final String ORIGINAL_POS_TRX = "2301-20260808-9999-O001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final int AMOUNT = 10_000;

    private ReversalServiceImpl reversalService;
    private VanReversalRepository reversalRepository;
    private VanApprovalRepository approvalRepository;

    @BeforeEach
    void setUp() {
        reversalRepository = mock(VanReversalRepository.class);
        approvalRepository = mock(VanApprovalRepository.class);
        VanTransactionIdGenerator vanTransactionIdGenerator = () -> "VAN-REVERSAL-001";
        ApprovalNumberGenerator approvalNumberGenerator = () -> "REV-APP-001";
        reversalService = new ReversalServiceImpl(
                reversalRepository,
                approvalRepository,
                vanTransactionIdGenerator,
                approvalNumberGenerator
        );
    }

    @Test
    void APPROVED_원승인은_REVERSED로_저장하고_SUCCESS를_반환한다() {
        ReversalCommand command = command();
        when(reversalRepository.findByReversalPosTrx(command.reversalPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.of(original(VanApprovalStatus.APPROVED, AMOUNT)));
        when(reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.empty());
        when(reversalRepository.save(any(VanReversal.class))).thenReturn(reversedLedger(command));

        ReversalResult result = reversalService.processReversal(command);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.SUCCESS);
        assertSavedReversed(command);
    }

    @Test
    void UNKNOWN_원승인도_REVERSED로_저장하고_SUCCESS를_반환한다() {
        ReversalCommand command = command();
        when(reversalRepository.findByReversalPosTrx(command.reversalPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.of(original(VanApprovalStatus.UNKNOWN, AMOUNT)));
        when(reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.empty());
        when(reversalRepository.save(any(VanReversal.class))).thenReturn(reversedLedger(command));

        ReversalResult result = reversalService.processReversal(command);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.SUCCESS);
        assertSavedReversed(command);
    }

    @Test
    void DECLINED_원승인은_REVERSAL_DECLINED와_ORIGINAL_NOT_REVERSIBLE을_반환한다() {
        ReversalCommand command = command();
        when(reversalRepository.findByReversalPosTrx(command.reversalPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.of(original(VanApprovalStatus.DECLINED, AMOUNT)));
        when(reversalRepository.save(any(VanReversal.class)))
                .thenReturn(declinedLedger(command, ReversalResultCode.ORIGINAL_NOT_REVERSIBLE.name()));

        ReversalResult result = reversalService.processReversal(command);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.ORIGINAL_NOT_REVERSIBLE);
        assertSavedDeclined(command, ReversalResultCode.ORIGINAL_NOT_REVERSIBLE);
    }

    @Test
    void 원승인이_없으면_REVERSAL_DECLINED와_ORIGINAL_NOT_FOUND를_반환한다() {
        ReversalCommand command = command();
        when(reversalRepository.findByReversalPosTrx(command.reversalPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.empty());
        when(reversalRepository.save(any(VanReversal.class)))
                .thenReturn(declinedLedger(command, ReversalResultCode.ORIGINAL_NOT_FOUND.name()));

        ReversalResult result = reversalService.processReversal(command);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.ORIGINAL_NOT_FOUND);
        assertSavedDeclined(command, ReversalResultCode.ORIGINAL_NOT_FOUND);
    }

    @Test
    void amount가_원승인과_다르면_REVERSAL_DECLINED와_ORIGINAL_MISMATCH를_반환한다() {
        ReversalCommand command = command();
        when(reversalRepository.findByReversalPosTrx(command.reversalPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.of(original(VanApprovalStatus.APPROVED, AMOUNT + 1)));
        when(reversalRepository.save(any(VanReversal.class)))
                .thenReturn(declinedLedger(command, ReversalResultCode.ORIGINAL_MISMATCH.name()));

        ReversalResult result = reversalService.processReversal(command);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.ORIGINAL_MISMATCH);
        assertSavedDeclined(command, ReversalResultCode.ORIGINAL_MISMATCH);
    }

    @Test
    void 같은_reversalPosTrx와_같은_payload면_저장된_결과를_replay한다() {
        ReversalCommand command = command();
        when(reversalRepository.findByReversalPosTrx(command.reversalPosTrx()))
                .thenReturn(Optional.of(reversedLedger(command)));

        ReversalResult result = reversalService.processReversal(command);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.SUCCESS);
        verify(approvalRepository, never()).findByPosTrxAndAttemptSeqForUpdate(any(), any(Integer.class));
        verify(reversalRepository, never()).save(any(VanReversal.class));
    }

    @Test
    void 같은_reversalPosTrx와_다른_payload면_conflict_예외를_던진다() {
        ReversalCommand command = command();
        ReversalCommand differentPayload = new ReversalCommand(
                command.reversalPosTrx(),
                "2301-20260808-9999-O999",
                command.originalAttemptSeq(),
                command.amount()
        );
        when(reversalRepository.findByReversalPosTrx(command.reversalPosTrx()))
                .thenReturn(Optional.of(reversedLedger(command)));

        assertThatThrownBy(() -> reversalService.processReversal(differentPayload))
                .isInstanceOf(ReversalRequestConflictException.class);
    }

    @Test
    void 같은_원승인에_기존_REVERSED가_있으면_새_row없이_ALREADY_REVERSED를_반환한다() {
        ReversalCommand command = command();
        VanReversal existing = reversedLedger(command);
        ReversalCommand follower = new ReversalCommand(
                "2301-20260808-9999-R002",
                command.originalPosTrx(),
                command.originalAttemptSeq(),
                command.amount()
        );
        when(reversalRepository.findByReversalPosTrx(follower.reversalPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                follower.originalPosTrx(),
                follower.originalAttemptSeq()
        )).thenReturn(Optional.of(original(VanApprovalStatus.APPROVED, AMOUNT)));
        when(reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                follower.originalPosTrx(),
                follower.originalAttemptSeq()
        )).thenReturn(Optional.of(existing));

        ReversalResult result = reversalService.processReversal(follower);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.ALREADY_REVERSED);
        assertThat(result.reversalPosTrx()).isEqualTo(follower.reversalPosTrx());
        verify(reversalRepository, never()).save(any(VanReversal.class));
    }

    @Test
    void 같은_원승인에_기존_REVERSAL_DECLINED가_있으면_기존_decline을_재응답한다() {
        ReversalCommand command = command();
        VanReversal existing = declinedLedger(command, ReversalResultCode.ORIGINAL_MISMATCH.name());
        ReversalCommand follower = new ReversalCommand(
                "2301-20260808-9999-R002",
                command.originalPosTrx(),
                command.originalAttemptSeq(),
                command.amount()
        );
        when(reversalRepository.findByReversalPosTrx(follower.reversalPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                follower.originalPosTrx(),
                follower.originalAttemptSeq()
        )).thenReturn(Optional.of(original(VanApprovalStatus.APPROVED, AMOUNT)));
        when(reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                follower.originalPosTrx(),
                follower.originalAttemptSeq()
        )).thenReturn(Optional.of(existing));

        ReversalResult result = reversalService.processReversal(follower);

        assertThat(result.reversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(result.resultCode()).isEqualTo(ReversalResultCode.ORIGINAL_MISMATCH);
        assertThat(result.declineCode()).isEqualTo(ReversalResultCode.ORIGINAL_MISMATCH.name());
        assertThat(result.reversalPosTrx()).isEqualTo(follower.reversalPosTrx());
        verify(reversalRepository, never()).save(any(VanReversal.class));
    }

    private ReversalCommand command() {
        return new ReversalCommand(
                REVERSAL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                AMOUNT
        );
    }

    private VanApproval original(
            VanApprovalStatus status,
            int amount
    ) {
        return VanApproval.builder()
                .vanTrxId("VAN-APPROVAL-001")
                .posTrx(ORIGINAL_POS_TRX)
                .attemptSeq(ORIGINAL_ATTEMPT_SEQ)
                .amount(amount)
                .cardBin("12345678")
                .cardLast4("1234")
                .approvalStatus(status)
                .approvalNo(status == VanApprovalStatus.APPROVED ? "APPROVAL-001" : null)
                .declineCode(status == VanApprovalStatus.DECLINED ? "05" : null)
                .processedAt(LocalDateTime.now())
                .build();
    }

    private VanReversal reversedLedger(ReversalCommand command) {
        return VanReversal.builder()
                .vanReversalTrxId("VAN-REVERSAL-001")
                .reversalPosTrx(command.reversalPosTrx())
                .originalPosTrx(command.originalPosTrx())
                .originalAttemptSeq(command.originalAttemptSeq())
                .amount(command.amount())
                .reversalStatus(VanReversalStatus.REVERSED)
                .reversalApprovalNo("REV-APP-001")
                .declineCode(null)
                .processedAt(LocalDateTime.now())
                .build();
    }

    private VanReversal declinedLedger(
            ReversalCommand command,
            String declineCode
    ) {
        return VanReversal.builder()
                .vanReversalTrxId("VAN-REVERSAL-DECLINED-001")
                .reversalPosTrx(command.reversalPosTrx())
                .originalPosTrx(command.originalPosTrx())
                .originalAttemptSeq(command.originalAttemptSeq())
                .amount(command.amount())
                .reversalStatus(VanReversalStatus.REVERSAL_DECLINED)
                .reversalApprovalNo(null)
                .declineCode(declineCode)
                .processedAt(LocalDateTime.now())
                .build();
    }

    private void assertSavedReversed(ReversalCommand command) {
        ArgumentCaptor<VanReversal> captor = ArgumentCaptor.forClass(VanReversal.class);
        verify(reversalRepository).save(captor.capture());

        VanReversal saved = captor.getValue();
        assertThat(saved.getReversalStatus()).isEqualTo(VanReversalStatus.REVERSED);
        assertThat(saved.getOriginalPosTrx()).isEqualTo(command.originalPosTrx());
        assertThat(saved.getOriginalAttemptSeq()).isEqualTo(command.originalAttemptSeq());
        assertThat(saved.getAmount()).isEqualTo(command.amount());
        assertThat(saved.getReversalApprovalNo()).isNotNull();
        assertThat(saved.getDeclineCode()).isNull();
    }

    private void assertSavedDeclined(
            ReversalCommand command,
            ReversalResultCode expectedDeclineCode
    ) {
        ArgumentCaptor<VanReversal> captor = ArgumentCaptor.forClass(VanReversal.class);
        verify(reversalRepository).save(captor.capture());

        VanReversal saved = captor.getValue();
        assertThat(saved.getReversalStatus()).isEqualTo(VanReversalStatus.REVERSAL_DECLINED);
        assertThat(saved.getOriginalPosTrx()).isEqualTo(command.originalPosTrx());
        assertThat(saved.getOriginalAttemptSeq()).isEqualTo(command.originalAttemptSeq());
        assertThat(saved.getAmount()).isEqualTo(command.amount());
        assertThat(saved.getReversalApprovalNo()).isNull();
        assertThat(saved.getDeclineCode()).isEqualTo(expectedDeclineCode.name());
    }
}
