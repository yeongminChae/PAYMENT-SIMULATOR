package com.chaeyeongmin.van_sim.transaction.reversal.service.impl;

import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.entity.VanCancel;
import com.chaeyeongmin.van_sim.ledger.cancel.repository.VanCancelRepository;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;
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
 *
 * <p>
 * 같은 원승인에 cancel이 이미 성공한 경우에는 reversal을 성공시키면 안 된다.
 * 그래서 원승인 lock 이후 van_cancel 원장도 확인하고, CANCELLED row가 있으면
 * 신규 reversal을 REVERSAL_DECLINED/ALREADY_CANCELLED로 저장한다.
 */
@Service
@Profile("postgres")
@RequiredArgsConstructor
public class ReversalServiceImpl implements ReversalService {

    private static final String ORIGINAL_NOT_FOUND = "ORIGINAL_NOT_FOUND";
    private static final String ORIGINAL_NOT_REVERSIBLE = "ORIGINAL_NOT_REVERSIBLE";
    private static final String ORIGINAL_MISMATCH = "ORIGINAL_MISMATCH";
    private static final String ALREADY_CANCELLED = "ALREADY_CANCELLED";

    private final VanReversalRepository reversalRepository;
    private final VanApprovalRepository approvalRepository;
    private final VanCancelRepository cancelRepository;
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

        // “lock 실패”가 아니라 원승인 row 자체가 VAN approval 원장에 없다면
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
        // 이 조회는 lock 이후에 수행해야 한다. 그래야 동시에 들어온 다른 reversal이 먼저 commit한 row를 볼 수 있다.
        Optional<VanReversal> existingByOriginal =
                reversalRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        // 같은 reversalPosTrx를 같은 내용으로 재요청한 건지 확인해서 commit한 row가 있다면
        // 새 row를 만들지 않고 저장된 결과를 그대로 다시 응답.
        if (existingByOriginal.isPresent()) {
            return alreadyProcessedResult(
                    command,
                    existingByOriginal.get()
            );
        }

        // 4-1. lock을 기다리는 동안 같은 원승인이 cancel로 처리됐는지 확인.
        // - reversal과 cancel은 서로 다른 원장 테이블에 저장되지만 같은 원승인에 대한 terminal 후속 거래다.
        // - 이미 CANCELLED가 확정된 원승인에 REVERSED까지 저장하면 cross-ledger invariant가 깨진다.
        // - 따라서 원승인 lock을 잡은 뒤 cancel 원장도 확인하고, 성공 cancel이 있으면 reversal은 거절 원장으로 남긴다.
        Optional<VanCancel> existingCancel =
                cancelRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        command.originalPosTrx(),
                        command.originalAttemptSeq()
                );

        if (existingCancel.isPresent()
                && existingCancel.get().getCancelStatus() == VanCancelStatus.CANCELLED) {

            return saveDeclined(
                    command,
                    "ALREADY_CANCELLED",
                    ReversalResultCode.ALREADY_CANCELLED
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
        // 이미 성공한 cancel도 4-1번에서 걸렀으므로 REVERSED와 CANCELLED가 동시에 생기지 않아야 한다.
        return saveReversed(command, ReversalResultCode.SUCCESS);
    }

    private boolean isReversible(VanApproval original) {
        // UNKNOWN은 승인 응답 유실 가능성이 있는 상태라 reversal로 복구할 수 있어야 한다.
        // DECLINED는 승인 사실이 없으므로 reversal 대상이 아니다.
        return switch (original.getApprovalStatus()) {
            case APPROVED, UNKNOWN -> true;
            case DECLINED -> false;
        };
    }

    private void assertSamePayload(
            VanReversal existingReversal,
            ReversalCommand command
    ) {
        // ReversalCommand에는 originalApprovalNo/originalVanTrxId가 없다.
        // 따라서 같은 reversalPosTrx replay 여부는 original 식별자와 amount만으로 판단한다.
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
        // 같은 original의 기존 REVERSED row를 발견한 follower 요청이다.
        // 저장 owner의 reversalPosTrx가 아니라 현재 요청 reversalPosTrx로 correlation을 유지한다.
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

        // 기존 original row가 REVERSAL_DECLINED였다면 새 row를 만들지 않고 저장된 decline 의미를 재응답한다.
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
        // 같은 reversalPosTrx replay는 "현재 요청이 기존 요청과 동일하다"는 의미이므로
        // 저장 row가 REVERSED면 최초 처리와 같은 SUCCESS로 재생한다.
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

        // declineCode는 영속화된 거절 사유이고, 응답에서는 다시 ReversalResultCode로 복원한다.
        // ALREADY_CANCELLED는 같은 원승인이 이미 cancel 성공으로 끝난 뒤 들어온 reversal 방어 결과다.
        return switch (declineCode) {
            case ORIGINAL_NOT_FOUND -> ReversalResultCode.ORIGINAL_NOT_FOUND;
            case ORIGINAL_NOT_REVERSIBLE -> ReversalResultCode.ORIGINAL_NOT_REVERSIBLE;
            case ORIGINAL_MISMATCH -> ReversalResultCode.ORIGINAL_MISMATCH;
            case ALREADY_CANCELLED -> ReversalResultCode.ALREADY_CANCELLED;
            default -> throw new IllegalStateException(
                    "Unsupported reversal declineCode: " + declineCode
            );
        };
    }

    private ReversalResult toResult(
            VanReversal reversal,
            ReversalResultCode resultCode
    ) {
        // 저장된 VAN reversal 원장을 현재 요청의 응답 DTO로 변환한다.
        // 같은 reversalPosTrx replay 경로에서는 저장 row의 reversalPosTrx가 그대로 응답된다.
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
        // 신규 reversal 성공 원장 생성.
        // 원승인 approvalStatus는 바꾸지 않고 van_reversal에 REVERSED 사실과 reversalApprovalNo만 남긴다.
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
        // Reversal 거절도 원장에 저장한다.
        // 이후 같은 reversalPosTrx 또는 같은 original 재조회가 들어오면 이 row로 같은 거절 의미를 재응답한다.
        // 이미 cancel 성공이 있는 경우도 ALREADY_CANCELLED declineCode로 이 경로에 기록한다.
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

        // 거절도 최종 처리 사실이므로 van_reversal 원장에 저장한다.
        // 이후 같은 reversalPosTrx replay나 같은 original follower 요청은 이 row를 기준으로 같은 거절 결과를 재응답한다.
        VanReversal saved = reversalRepository.save(reversal);
        return toResult(
                saved,
                resultCode
        );
    }

    private LocalDateTime now() {
        // PostgreSQL timestamp와 Java LocalDateTime 비교 흔들림을 줄이기 위해 마이크로초 단위로 맞춘다.
        return LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
