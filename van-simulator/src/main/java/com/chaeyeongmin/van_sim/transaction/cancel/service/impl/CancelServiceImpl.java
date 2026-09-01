package com.chaeyeongmin.van_sim.transaction.cancel.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import com.chaeyeongmin.van_sim.transaction.cancel.CancelService;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.exception.CancelRequestConflictException;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
import com.chaeyeongmin.van_sim.transaction.cancel.service.support.CancelApprovalNumberGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
@Profile("postgres")
@RequiredArgsConstructor
public class CancelServiceImpl implements CancelService {

    private static final String ORIGINAL_NOT_FOUND = "ORIGINAL_NOT_FOUND";
    private static final String ORIGINAL_NOT_APPROVED = "ORIGINAL_NOT_APPROVED";
    private static final String ORIGINAL_MISMATCH = "ORIGINAL_MISMATCH";

    private final VanCancelRepository cancelRepository;
    private final VanApprovalRepository approvalRepository;
    private final VanTransactionIdGenerator vanTransactionIdGenerator;
    private final CancelApprovalNumberGenerator cancelApprovalNumberGenerator;

    @Override
    @Transactional
    public CancelResult processCancel(CancelCommand command) {

        // 1. 동일 cancelPosTrx에 대한 빠른 idempotency check
        Optional<VanCancel> existing =
                cancelRepository.findByCancelPosTrx(command.cancelPosTrx());

        if (existing.isPresent()) {
            VanCancel existingCancel = existing.get();

            assertSamePayload(existingCancel, command);

            return toResult(
                    existingCancel,
                    resultCodeOf(existingCancel)
            );
        }

        // 2. 원승인 row lock
        Optional<VanApproval> originalOptional =
                approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        if (originalOptional.isEmpty()) {
            return saveDeclined(
                    command,
                    ORIGINAL_NOT_FOUND,
                    CancelResultCode.ORIGINAL_NOT_FOUND
            );
        }

        VanApproval original = originalOptional.get();

        // 3. lock을 기다리는 동안 같은 cancelPosTrx가 처리됐는지 재확인
        Optional<VanCancel> existingAfterLock =
                cancelRepository.findByCancelPosTrx(command.cancelPosTrx());

        if (existingAfterLock.isPresent()) {
            VanCancel existingCancel = existingAfterLock.get();

            assertSamePayload(existingCancel, command);

            return toResult(
                    existingCancel,
                    resultCodeOf(existingCancel)
            );
        }

        // 4. lock을 기다리는 동안 같은 원승인이 다른 cancel로 처리됐는지 재확인
        Optional<VanCancel> existingByOriginal =
                cancelRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        if (existingByOriginal.isPresent()) {
            return alreadyProcessedResult(
                    command,
                    existingByOriginal.get()
            );
        }

        // 5. 실제 승인된 거래인지 확인
        if (original.getApprovalStatus() != VanApprovalStatus.APPROVED) {
            return saveDeclined(
                    command,
                    ORIGINAL_NOT_APPROVED,
                    CancelResultCode.ORIGINAL_NOT_APPROVED
            );
        }

        // 6. 요청에 포함된 원승인 정보가 VAN 원장과 일치하는지 확인
        if (isOriginalMismatch(original, command)) {
            return saveDeclined(
                    command,
                    ORIGINAL_MISMATCH,
                    CancelResultCode.ORIGINAL_MISMATCH
            );
        }

        // 7. 신규 정상 취소
        return saveCancelled(
                command,
                CancelResultCode.SUCCESS
        );
    }

    private boolean isOriginalMismatch(
            VanApproval original,
            CancelCommand command
    ) {
        return original.getAmount() != command.amount()
                || original.getVanTrxId().equals(command.originalVanTrxId()) == false
                || original.getApprovalNo().equals(command.originalApprovalNo()) == false
                ;
    }

    private void assertSamePayload(
            VanCancel existingCancel,
            CancelCommand command
    ) {
        if (existingCancel.getOriginalPosTrx().equals(command.originalPosTrx()) == false
                || existingCancel.getOriginalAttemptSeq() != command.originalAttemptSeq()
                || existingCancel.getOriginalVanTrxId().equals(command.originalVanTrxId()) == false
                || existingCancel.getOriginalApprovalNo().equals(command.originalApprovalNo()) == false
                || existingCancel.getAmount() != command.amount()) {

            throw new CancelRequestConflictException("CANCEL_REQUEST_CONFLICT");
        }
    }

    /**
     * 같은 원승인을 다른 cancelPosTrx가 다시 취소하려는 경우.
     * <p>
     * 새 VAN 취소 거래는 만들지 않는다.
     * 응답 correlation은 현재 요청 cancelPosTrx를 사용하고,
     * 실제 처리 사실은 기존 VAN Cancel 원장에서 가져온다.
     */
    private CancelResult alreadyProcessedResult(
            CancelCommand command,
            VanCancel existingCancel
    ) {
        if (existingCancel.getCancelStatus() == VanCancelStatus.CANCELLED) {
            return new CancelResult(
                    existingCancel.getVanCancelTrxId(),
                    command.cancelPosTrx(),
                    command.originalPosTrx(),
                    command.originalAttemptSeq(),
                    VanCancelStatus.CANCELLED,
                    CancelResultCode.ALREADY_CANCELLED,
                    existingCancel.getCancelApprovalNo(),
                    null,
                    existingCancel.getProcessedAt()
            );
        }

        return new CancelResult(
                existingCancel.getVanCancelTrxId(),
                command.cancelPosTrx(),
                command.originalPosTrx(),
                command.originalAttemptSeq(),
                VanCancelStatus.CANCEL_DECLINED,
                resultCodeFromDeclineCode(existingCancel.getDeclineCode()),
                null,
                existingCancel.getDeclineCode(),
                existingCancel.getProcessedAt()
        );
    }

    /**
     * 동일 cancelPosTrx replay 시 저장된 VAN 원장을
     * 다시 현재 응답 형태로 변환한다.
     */
    private CancelResultCode resultCodeOf(VanCancel cancel) {
        if (cancel.getCancelStatus() == VanCancelStatus.CANCELLED) {
            return CancelResultCode.SUCCESS;
        }

        if (cancel.getCancelStatus() == VanCancelStatus.CANCEL_DECLINED) {
            return resultCodeFromDeclineCode(cancel.getDeclineCode());
        }

        throw new IllegalStateException(
                "Unsupported cancel status: " + cancel.getCancelStatus()
        );
    }

    private CancelResultCode resultCodeFromDeclineCode(String declineCode) {
        if (declineCode == null) {
            throw new IllegalStateException(
                    "Declined cancel must have declineCode"
            );
        }

        return switch (declineCode) {
            case ORIGINAL_NOT_FOUND -> CancelResultCode.ORIGINAL_NOT_FOUND;

            case ORIGINAL_NOT_APPROVED -> CancelResultCode.ORIGINAL_NOT_APPROVED;

            case ORIGINAL_MISMATCH -> CancelResultCode.ORIGINAL_MISMATCH;

            default -> throw new IllegalStateException(
                    "Unsupported cancel declineCode: " + declineCode
            );
        };
    }

    private CancelResult toResult(
            VanCancel cancel,
            CancelResultCode resultCode
    ) {
        return new CancelResult(
                cancel.getVanCancelTrxId(),
                cancel.getCancelPosTrx(),
                cancel.getOriginalPosTrx(),
                cancel.getOriginalAttemptSeq(),
                cancel.getCancelStatus(),
                resultCode,
                cancel.getCancelApprovalNo(),
                cancel.getDeclineCode(),
                cancel.getProcessedAt()
        );
    }

    private CancelResult saveCancelled(
            CancelCommand command,
            CancelResultCode resultCode
    ) {
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

        return toResult(
                cancelRepository.save(cancel),
                resultCode
        );
    }

    private CancelResult saveDeclined(
            CancelCommand command,
            String declineCode,
            CancelResultCode resultCode
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

        return toResult(
                cancelRepository.save(cancel),
                resultCode
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
