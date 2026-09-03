package com.chaeyeongmin.van_sim.transaction.reversal.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.reversal.entity.VanReversal;
import com.chaeyeongmin.van_sim.ledger.reversal.repository.VanReversalRepository;
import com.chaeyeongmin.van_sim.ledger.reversal.status.ReversalResultCode;
import com.chaeyeongmin.van_sim.ledger.reversal.status.VanReversalStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import com.chaeyeongmin.van_sim.transaction.reversal.ReversalService;
import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.exception.ReversalRequestConflictException;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * PostgreSQL 프로필에서 사용하는 VAN reversal 서비스다.
 *
 * <p>
 * Reversal은 승인 응답 유실/장애 복구를 위한 별도 거래다. 원승인이 APPROVED 또는 UNKNOWN이면
 * reversal 가능한 대상으로 보고, 원승인 van_approval row는 수정하지 않고 van_reversal에 별도 처리 사실만 남긴다.
 *
 * <p>
 * 같은 원승인에 대한 동시 reversal은 van_approval row의 PESSIMISTIC_WRITE lock으로 직렬화한다.
 * lock을 기다리는 동안 먼저 commit된 reversal을 반영하기 위해 lock 이후 재조회도 수행한다.
 */
@Service
@Profile("postgres")
@RequiredArgsConstructor
public class ReversalServiceImpl implements ReversalService {

    private static final String ORIGINAL_NOT_FOUND = "ORIGINAL_NOT_FOUND";
    private static final String ORIGINAL_NOT_REVERSIBLE = "ORIGINAL_NOT_REVERSIBLE";
    private static final String ORIGINAL_MISMATCH = "ORIGINAL_MISMATCH";

    private final VanReversalRepository reversalRepository;
    private final VanApprovalRepository approvalRepository;
    private final VanTransactionIdGenerator vanTransactionIdGenerator;
    private final ApprovalNumberGenerator approvalNumberGenerator;

    @Override
    @Transactional
    public ReversalResult processReversal(ReversalCommand command) {

        // 1. 동일 reversalPosTrx 빠른 멱등성 검사.
        // 같은 거래번호라도 payload가 다르면 재응답이 아니라 거래번호 재사용 충돌이다.
        Optional<VanReversal> existing =
                reversalRepository.findByReversalPosTrx(command.reversalPosTrx());

        if (existing.isPresent()) {
            VanReversal existingReversal = existing.get();

            assertSamePayload(existingReversal, command);

            return toResult(
                    existingReversal,
                    resultCodeOf(existingReversal)
            );
        }

        // 2. 원승인 row lock.
        // 서로 다른 reversalPosTrx가 같은 원승인을 복구하려는 경우 여기서 직렬화된다.
        Optional<VanApproval> originalOptional =
                approvalRepository.findByPosTrxAndAttemptSeqForUpdate(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        if (originalOptional.isEmpty()) {
            return saveDeclined(
                    command,
                    ORIGINAL_NOT_FOUND,
                    ReversalResultCode.ORIGINAL_NOT_FOUND
            );
        }

        VanApproval original = originalOptional.get();

        // 3. lock 대기 중 같은 reversalPosTrx가 commit됐는지 다시 확인한다.
        Optional<VanReversal> existingAfterLock =
                reversalRepository.findByReversalPosTrx(command.reversalPosTrx());

        if (existingAfterLock.isPresent()) {
            VanReversal existingReversal = existingAfterLock.get();

            assertSamePayload(existingReversal, command);

            return toResult(
                    existingReversal,
                    resultCodeOf(existingReversal)
            );
        }

        // 4. 같은 원승인에 이미 reversal 사실이 있으면 새 row를 만들지 않는다.
        Optional<VanReversal> existingByOriginal =
                reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        if (existingByOriginal.isPresent()) {
            return alreadyProcessedResult(
                    command,
                    existingByOriginal.get()
            );
        }

        // 5. 원승인 상태 판단.
        // APPROVED뿐 아니라 UNKNOWN도 reversal 가능하다. 승인 응답 유실 복구가 reversal의 목적이기 때문이다.
        if (isReversible(original) == false) {
            return saveDeclined(
                    command,
                    ORIGINAL_NOT_REVERSIBLE,
                    ReversalResultCode.ORIGINAL_NOT_REVERSIBLE
            );
        }

        // 6. Reversal은 승인번호/VAN 거래번호를 받지 않으므로 amount만 payload 일치 기준으로 사용한다.
        if (original.getAmount() != command.amount()) {
            return saveDeclined(
                    command,
                    ORIGINAL_MISMATCH,
                    ReversalResultCode.ORIGINAL_MISMATCH
            );
        }

        // 7. 신규 reversal 성공.
        // 원승인 row는 수정하지 않고 van_reversal에 REVERSED 사실만 저장한다.
        return saveReversed(command, ReversalResultCode.SUCCESS);
    }

    private boolean isReversible(VanApproval original) {
        return switch (original.getApprovalStatus()) {
            case APPROVED, UNKNOWN -> true;
            case DECLINED -> false;
        };
    }

    private void assertSamePayload(
            VanReversal existingReversal,
            ReversalCommand command
    ) {
        if (existingReversal.getOriginalPosTrx().equals(command.originalPosTrx()) == false
                || existingReversal.getOriginalAttemptSeq() != command.originalAttemptSeq()
                || existingReversal.getAmount() != command.amount()) {

            throw new ReversalRequestConflictException("REVERSAL_REQUEST_CONFLICT");
        }
    }

    private ReversalResult alreadyProcessedResult(
            ReversalCommand command,
            VanReversal existingReversal
    ) {
        if (existingReversal.getReversalStatus() == VanReversalStatus.REVERSED) {
            return new ReversalResult(
                    existingReversal.getVanReversalTrxId(),
                    command.reversalPosTrx(),
                    command.originalPosTrx(),
                    command.originalAttemptSeq(),
                    command.amount(),
                    VanReversalStatus.REVERSED,
                    ReversalResultCode.ALREADY_REVERSED,
                    existingReversal.getReversalApprovalNo(),
                    null,
                    existingReversal.getProcessedAt()
            );
        }

        return new ReversalResult(
                existingReversal.getVanReversalTrxId(),
                command.reversalPosTrx(),
                command.originalPosTrx(),
                command.originalAttemptSeq(),
                command.amount(),
                VanReversalStatus.REVERSAL_DECLINED,
                resultCodeFromDeclineCode(existingReversal.getDeclineCode()),
                null,
                existingReversal.getDeclineCode(),
                existingReversal.getProcessedAt()
        );
    }

    private ReversalResultCode resultCodeOf(VanReversal reversal) {
        if (reversal.getReversalStatus() == VanReversalStatus.REVERSED) {
            return ReversalResultCode.SUCCESS;
        }

        if (reversal.getReversalStatus() == VanReversalStatus.REVERSAL_DECLINED) {
            return resultCodeFromDeclineCode(reversal.getDeclineCode());
        }

        throw new IllegalStateException(
                "Unsupported reversal status: " + reversal.getReversalStatus()
        );
    }

    private ReversalResultCode resultCodeFromDeclineCode(String declineCode) {
        if (declineCode == null) {
            throw new IllegalStateException(
                    "Declined reversal must have declineCode"
            );
        }

        return switch (declineCode) {
            case ORIGINAL_NOT_FOUND -> ReversalResultCode.ORIGINAL_NOT_FOUND;
            case ORIGINAL_NOT_REVERSIBLE -> ReversalResultCode.ORIGINAL_NOT_REVERSIBLE;
            case ORIGINAL_MISMATCH -> ReversalResultCode.ORIGINAL_MISMATCH;
            default -> throw new IllegalStateException(
                    "Unsupported reversal declineCode: " + declineCode
            );
        };
    }

    private ReversalResult toResult(
            VanReversal reversal,
            ReversalResultCode resultCode
    ) {
        return new ReversalResult(
                reversal.getVanReversalTrxId(),
                reversal.getReversalPosTrx(),
                reversal.getOriginalPosTrx(),
                reversal.getOriginalAttemptSeq(),
                reversal.getAmount(),
                reversal.getReversalStatus(),
                resultCode,
                reversal.getReversalApprovalNo(),
                reversal.getDeclineCode(),
                reversal.getProcessedAt()
        );
    }

    private ReversalResult saveReversed(
            ReversalCommand command,
            ReversalResultCode resultCode
    ) {
        VanReversal reversal = VanReversal.builder()
                .vanReversalTrxId(vanTransactionIdGenerator.generate())
                .reversalPosTrx(command.reversalPosTrx())
                .originalPosTrx(command.originalPosTrx())
                .originalAttemptSeq(command.originalAttemptSeq())
                .amount(command.amount())
                .reversalStatus(VanReversalStatus.REVERSED)
                .reversalApprovalNo(approvalNumberGenerator.generate())
                .declineCode(null)
                .processedAt(now())
                .build();

        return toResult(
                reversalRepository.save(reversal),
                resultCode
        );
    }

    private ReversalResult saveDeclined(
            ReversalCommand command,
            String declineCode,
            ReversalResultCode resultCode
    ) {
        VanReversal reversal = VanReversal.builder()
                .vanReversalTrxId(vanTransactionIdGenerator.generate())
                .reversalPosTrx(command.reversalPosTrx())
                .originalPosTrx(command.originalPosTrx())
                .originalAttemptSeq(command.originalAttemptSeq())
                .amount(command.amount())
                .reversalStatus(VanReversalStatus.REVERSAL_DECLINED)
                .reversalApprovalNo(null)
                .declineCode(declineCode)
                .processedAt(now())
                .build();

        return toResult(
                reversalRepository.save(reversal),
                resultCode
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
