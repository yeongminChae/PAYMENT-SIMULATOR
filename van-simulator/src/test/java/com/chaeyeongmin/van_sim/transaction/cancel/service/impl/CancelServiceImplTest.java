package com.chaeyeongmin.van_sim.transaction.cancel.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.ledger.reversal.repository.VanReversalRepository;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.exception.CancelRequestConflictException;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
import com.chaeyeongmin.van_sim.transaction.cancel.service.support.CancelApprovalNumberGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelServiceImplTest {

    @Mock
    private VanCancelRepository cancelRepository;

    @Mock
    private VanApprovalRepository approvalRepository;

    @Mock
    private VanReversalRepository reversalRepository;

    @Mock
    private VanTransactionIdGenerator vanTransactionIdGenerator;

    @Mock
    private CancelApprovalNumberGenerator cancelApprovalNumberGenerator;

    @InjectMocks
    private CancelServiceImpl cancelService;

    @Test
    @DisplayName("APPROVED 원승인은 정상 취소되어 van_cancel 원장에 저장된다")
    void processCancel_originalApproved_shouldSaveCancelledLedger() {
        CancelCommand command = newCommand("0001");
        VanApproval originalApproval = approvedOriginalApproval(command);

        when(cancelRepository.findByCancelPosTrx(command.cancelPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.of(originalApproval));
        when(vanTransactionIdGenerator.generate())
                .thenReturn("VAN-CANCEL-TEST-001");
        when(cancelApprovalNumberGenerator.generate())
                .thenReturn("CANCEL-APPROVAL-TEST-001");
        when(cancelRepository.save(any(VanCancel.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CancelResult result = cancelService.processCancel(command);

        assertThat(result).isNotNull();
        assertThat(result.cancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
        assertThat(result.vanCancelTrxId()).isEqualTo("VAN-CANCEL-TEST-001");
        assertThat(result.cancelApprovalNo()).isEqualTo("CANCEL-APPROVAL-TEST-001");
        assertThat(result.declineCode()).isNull();

        verify(cancelRepository).save(any(VanCancel.class));
    }

    @Test
    @DisplayName("동일 cancelPosTrx와 동일 payload 재요청은 기존 취소 결과를 재사용한다")
    void processCancel_sameCancelPosTrxAndSamePayload_shouldReplayExistingResult() {
        CancelCommand command = newCommand("0002");
        VanCancel existingCancel = cancelledLedger(command);

        when(cancelRepository.findByCancelPosTrx(command.cancelPosTrx()))
                .thenReturn(Optional.of(existingCancel));

        CancelResult result = cancelService.processCancel(command);

        assertThat(result).isNotNull();
        assertThat(result.vanCancelTrxId()).isEqualTo(existingCancel.getVanCancelTrxId());
        assertThat(result.cancelApprovalNo()).isEqualTo(existingCancel.getCancelApprovalNo());
        assertThat(result.processedAt()).isEqualTo(existingCancel.getProcessedAt());

        verify(cancelRepository, never()).save(any(VanCancel.class));
        verify(approvalRepository, never()).findByPosTrxAndAttemptSeqForUpdate(any(), anyInt());
    }

    @Test
    @DisplayName("동일 cancelPosTrx에 다른 payload가 들어오면 충돌로 처리한다")
    void processCancel_sameCancelPosTrxAndDifferentPayload_shouldThrowConflict() {
        CancelCommand originalCommand = newCommand("0003");
        VanCancel existingCancel = cancelledLedger(originalCommand);
        CancelCommand conflictCommand = new CancelCommand(
                originalCommand.cancelPosTrx(),
                originalCommand.originalPosTrx(),
                originalCommand.originalAttemptSeq(),
                originalCommand.originalVanTrxId(),
                originalCommand.originalApprovalNo(),
                20_000
        );

        when(cancelRepository.findByCancelPosTrx(conflictCommand.cancelPosTrx()))
                .thenReturn(Optional.of(existingCancel));

        assertThatThrownBy(() -> cancelService.processCancel(conflictCommand))
                .isInstanceOf(CancelRequestConflictException.class);

        verify(cancelRepository, never()).save(any(VanCancel.class));
        verify(approvalRepository, never()).findByPosTrxAndAttemptSeqForUpdate(any(), anyInt());
    }

    @Test
    @DisplayName("원승인이 존재하지 않으면 ORIGINAL_NOT_FOUND로 취소 거절 원장을 저장한다")
    void processCancel_originalNotFound_shouldSaveDeclinedLedger() {
        stubSaveReturnsArgument();

        CancelCommand command = newCommand("0004");

        when(cancelRepository.findByCancelPosTrx(command.cancelPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.empty());

        CancelResult result = cancelService.processCancel(command);

        assertThat(result.cancelStatus()).isEqualTo(VanCancelStatus.CANCEL_DECLINED);
        assertThat(result.cancelApprovalNo()).isNull();
        assertThat(result.declineCode()).isEqualTo("ORIGINAL_NOT_FOUND");

        verify(cancelRepository).save(any(VanCancel.class));
    }

    @Test
    @DisplayName("원승인이 APPROVED가 아니면 ORIGINAL_NOT_APPROVED로 취소 거절한다")
    void processCancel_originalNotApproved_shouldDecline() {
        stubSaveReturnsArgument();

        CancelCommand command = newCommand("0005");
        VanApproval originalApproval = originalApproval(
                command,
                VanApprovalStatus.DECLINED,
                command.amount(),
                command.originalVanTrxId(),
                null
        );

        when(cancelRepository.findByCancelPosTrx(command.cancelPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.of(originalApproval));

        CancelResult result = cancelService.processCancel(command);

        assertThat(result.cancelStatus()).isEqualTo(VanCancelStatus.CANCEL_DECLINED);
        assertThat(result.cancelApprovalNo()).isNull();
        assertThat(result.declineCode()).isEqualTo("ORIGINAL_NOT_APPROVED");

        verify(cancelRepository).save(any(VanCancel.class));
    }

    @Test
    @DisplayName("원승인 정보가 요청과 다르면 ORIGINAL_MISMATCH로 취소 거절한다")
    void processCancel_originalPayloadMismatch_shouldDecline() {
        stubSaveReturnsArgument();

        CancelCommand command = newCommand("0006");
        VanApproval originalApproval = originalApproval(
                command,
                VanApprovalStatus.APPROVED,
                20_000,
                command.originalVanTrxId(),
                command.originalApprovalNo()
        );

        when(cancelRepository.findByCancelPosTrx(command.cancelPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                command.originalPosTrx(),
                command.originalAttemptSeq()
        )).thenReturn(Optional.of(originalApproval));

        CancelResult result = cancelService.processCancel(command);

        assertThat(result.cancelStatus()).isEqualTo(VanCancelStatus.CANCEL_DECLINED);
        assertThat(result.cancelApprovalNo()).isNull();
        assertThat(result.declineCode()).isEqualTo("ORIGINAL_MISMATCH");

        verify(cancelRepository).save(any(VanCancel.class));
    }

    @Test
    @DisplayName("같은 원승인에 다른 cancelPosTrx가 들어오면 새 취소를 생성하지 않는다")
    void processCancel_sameOriginalWithDifferentCancelPosTrx_shouldReuseExistingCancel() {
        CancelCommand existingCommand = newCommand("0007");
        VanCancel existingCancel = cancelledLedger(existingCommand);

        CancelCommand newCommand = new CancelCommand(
                "2301-20260808-9999-0008",
                existingCommand.originalPosTrx(),
                existingCommand.originalAttemptSeq(),
                existingCommand.originalVanTrxId(),
                existingCommand.originalApprovalNo(),
                existingCommand.amount()
        );
        VanApproval originalApproval = approvedOriginalApproval(newCommand);

        when(cancelRepository.findByCancelPosTrx(newCommand.cancelPosTrx()))
                .thenReturn(Optional.empty());
        when(approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                newCommand.originalPosTrx(),
                newCommand.originalAttemptSeq()
        )).thenReturn(Optional.of(originalApproval));
        when(cancelRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                newCommand.originalPosTrx(),
                newCommand.originalAttemptSeq()
        )).thenReturn(Optional.of(existingCancel));

        CancelResult result = cancelService.processCancel(newCommand);

        assertThat(result).isNotNull();
        assertThat(result.cancelStatus()).isEqualTo(VanCancelStatus.CANCELLED);
        assertThat(result.cancelPosTrx()).isEqualTo(newCommand.cancelPosTrx());
        assertThat(result.resultCode()).isEqualTo(CancelResultCode.ALREADY_CANCELLED);
        assertThat(result.vanCancelTrxId()).isEqualTo(existingCancel.getVanCancelTrxId());
        assertThat(result.cancelApprovalNo()).isEqualTo(existingCancel.getCancelApprovalNo());
        assertThat(result.processedAt()).isEqualTo(existingCancel.getProcessedAt());
        verify(cancelRepository, never()).save(any(VanCancel.class));
        verify(vanTransactionIdGenerator, never()).generate();
        verify(cancelApprovalNumberGenerator, never()).generate();

    }

    private void stubSaveReturnsArgument() {
        when(cancelRepository.save(any(VanCancel.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CancelCommand newCommand(String cancelPosTrxSuffix) {
        return new CancelCommand(
                "2301-20260808-9999-" + cancelPosTrxSuffix,
                "2301-20260808-9999-1001",
                1,
                "VAN-APPROVAL-ORIGINAL-001",
                "APPROVAL-ORIGINAL-001",
                10_000
        );
    }

    private VanApproval approvedOriginalApproval(CancelCommand command) {
        return originalApproval(
                command,
                VanApprovalStatus.APPROVED,
                command.amount(),
                command.originalVanTrxId(),
                command.originalApprovalNo()
        );
    }

    private VanApproval originalApproval(
            CancelCommand command,
            VanApprovalStatus status,
            int amount,
            String vanTrxId,
            String approvalNo
    ) {
        return VanApproval.builder()
                .vanTrxId(vanTrxId)
                .posTrx(command.originalPosTrx())
                .attemptSeq(command.originalAttemptSeq())
                .amount(amount)
                .cardBin("12345678")
                .cardLast4("5678")
                .approvalStatus(status)
                .approvalNo(approvalNo)
                .declineCode(status == VanApprovalStatus.DECLINED ? "D001" : null)
                .processedAt(LocalDateTime.of(2026, 8, 31, 10, 0))
                .build();
    }

    private VanCancel cancelledLedger(CancelCommand command) {
        return VanCancel.builder()
                .vanCancelTrxId("VAN-CANCEL-EXISTING-001")
                .cancelPosTrx(command.cancelPosTrx())
                .originalPosTrx(command.originalPosTrx())
                .originalAttemptSeq(command.originalAttemptSeq())
                .originalVanTrxId(command.originalVanTrxId())
                .originalApprovalNo(command.originalApprovalNo())
                .amount(command.amount())
                .cancelStatus(VanCancelStatus.CANCELLED)
                .cancelApprovalNo("CANCEL-APPROVAL-EXISTING-001")
                .declineCode(null)
                .processedAt(LocalDateTime.of(2026, 8, 31, 11, 0))
                .build();
    }

}
