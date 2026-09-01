package com.chaeyeongmin.van_sim.transaction.cancel.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.transaction.cancel.CancelService;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.exception.CancelRequestConflictException;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import com.chaeyeongmin.van_sim.transaction.cancel.service.support.CancelApprovalNumberGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static java.time.LocalDateTime.now;

@Service
@Profile("postgres")
@RequiredArgsConstructor
public class CancelServiceImpl implements CancelService {

    private final VanCancelRepository cancelRepository;
    private final VanApprovalRepository approvalRepository;
    private final VanTransactionIdGenerator vanTransactionIdGenerator;
    private final CancelApprovalNumberGenerator cancelApprovalNumberGenerator;

    @Override
    @Transactional
    public CancelResult processCancel(CancelCommand command) {

        // 1. 같은 Cancel command 재전송인지 확인
        Optional<VanCancel> existing = cancelRepository.findByCancelPosTrx(command.cancelPosTrx());

        if (existing.isPresent()) {
            VanCancel existingCancel = existing.get();
            assertSamePayload(existingCancel, command);

            return toResult(existingCancel);
        }

        // 2. 원승인 존재 여부
        Optional<VanApproval> originalOptional =
                approvalRepository.findByPosTrxAndAttemptSeq(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        if (originalOptional.isEmpty()) {
            return saveDeclined(command, "ORIGINAL_NOT_FOUND");
        }

        VanApproval original = originalOptional.get();

        // 3. 실제 승인됐던 거래인지
        if (original.getApprovalStatus() != VanApprovalStatus.APPROVED) {
            return saveDeclined(command, "ORIGINAL_NOT_APPROVED");
        }

        // 4. 요청이 원승인 정보와 일치하는지
        if (isOriginalMismatch(original, command)) {
            return saveDeclined(command, "ORIGINAL_MISMATCH");
        }

        Optional<VanCancel> existingByOriginal =
                cancelRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        if (existingByOriginal.isPresent()) {
            return toResult(existingByOriginal.get());
        }

        // 5. 정상 취소
        return saveCancelled(command);
    }

    private boolean isOriginalMismatch(VanApproval original, CancelCommand command) {
        return original.getAmount() != command.amount()
                || original.getVanTrxId().equals(command.originalVanTrxId()) == false
                || original.getApprovalNo().equals(command.originalApprovalNo()) == false;
    }

    private void assertSamePayload(VanCancel existingCancel, CancelCommand command) {
        if (existingCancel.getOriginalPosTrx().equals(command.originalPosTrx()) == false
                || existingCancel.getOriginalAttemptSeq() != command.originalAttemptSeq()
                || !existingCancel.getOriginalVanTrxId().equals(command.originalVanTrxId())
                || !existingCancel.getOriginalApprovalNo().equals(command.originalApprovalNo())
                || existingCancel.getAmount() != command.amount()) {
            throw new CancelRequestConflictException("CANCEL_REQUEST_CONFLICT");
        }
    }

    private CancelResult saveCancelled(CancelCommand command) {
        VanCancel cancel = VanCancel.builder()
                .vanCancelTrxId(vanTransactionIdGenerator.generate())
                .cancelPosTrx(command.cancelPosTrx())
                .originalPosTrx(command.originalPosTrx())
                .originalAttemptSeq(command.originalAttemptSeq())
                .originalVanTrxId(command.originalVanTrxId())
                .originalApprovalNo(command.originalApprovalNo())
                .amount(command.amount())
                .cancelStatus(VanCancelStatus.CANCELLED)
                .cancelApprovalNo(cancelApprovalNumberGenerator.generate())
                .declineCode(null)
                .processedAt(now())
                .build();

        return toResult(cancelRepository.save(cancel));
    }

    private CancelResult saveDeclined(
            CancelCommand command,
            String declineCode
    ) {
        VanCancel cancel = VanCancel.builder()
                .vanCancelTrxId(vanTransactionIdGenerator.generate())
                .cancelPosTrx(command.cancelPosTrx())
                .originalPosTrx(command.originalPosTrx())
                .originalAttemptSeq(command.originalAttemptSeq())
                .originalVanTrxId(command.originalVanTrxId())
                .originalApprovalNo(command.originalApprovalNo())
                .amount(command.amount())
                .cancelStatus(VanCancelStatus.CANCEL_DECLINED)
                .cancelApprovalNo(null)
                .declineCode(declineCode)
                .processedAt(now())
                .build();

        return toResult(cancelRepository.save(cancel));
    }

    private CancelResult toResult(VanCancel cancel) {
        return new CancelResult(
                cancel.getVanCancelTrxId(),
                cancel.getCancelPosTrx(),
                cancel.getOriginalPosTrx(),
                cancel.getOriginalAttemptSeq(),
                cancel.getCancelStatus(),
                cancel.getCancelApprovalNo(),
                cancel.getDeclineCode(),
                cancel.getProcessedAt()
        );
    }

}
