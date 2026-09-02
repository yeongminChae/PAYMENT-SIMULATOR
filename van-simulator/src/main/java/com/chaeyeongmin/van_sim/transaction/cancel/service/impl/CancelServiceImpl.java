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

/**
 * PostgreSQL 프로필에서 사용하는 VAN 취소 서비스다.
 *
 * <p>
 * 이 서비스는 VAN 내부 원장 기준의 전체취소를 처리한다. 외부 Payment 서버 입장에서는 서로 다른
 * cancelPosTrx가 동시에 들어올 수 있지만, VAN 정책상 하나의 원승인(posTrx + attemptSeq)에는
 * 하나의 취소 원장만 허용한다.
 *
 * <p>
 * 동시성 제어의 기준은 cancelPosTrx가 아니라 원승인 row다. 같은 원승인을 취소하는 두 요청은
 * {@link VanApprovalRepository#findByPosTrxAndAttemptSeqForUpdate(String, int)}에서 같은
 * van_approval row의 PESSIMISTIC_WRITE lock을 경쟁한다. lock을 얻은 뒤에는 cancel 원장을
 * 다시 조회해서, 기다리는 동안 먼저 처리된 취소를 반드시 반영한다.
 */
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

        // 1. 동일 cancelPosTrx에 대한 빠른 idempotency check.
        // - 이미 같은 취소 거래번호로 저장된 row가 있으면 원승인 lock을 잡을 필요가 없다.
        // - 단, 같은 cancelPosTrx라도 payload가 다르면 멱등 재응답이 아니라 거래번호 재사용 충돌이다.
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

        // 2. 원승인 row lock.
        // - 서로 다른 cancelPosTrx(C001/C002)가 같은 원승인(A001/1)을 취소할 때 여기서 직렬화된다.
        // - lock 없이 아래 기존 취소 조회/신규 저장을 실행하면 두 요청이 동시에 "기존 취소 없음"을 볼 수 있다.
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

        // 3. lock을 기다리는 동안 같은 cancelPosTrx가 처리됐는지 재확인.
        // - 1번 사전검사 이후 현재 thread가 lock 대기하는 사이 같은 cancelPosTrx가 commit됐을 수 있다.
        // - lock 이후 재조회 결과가 최종 판단 기준이다.
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

        // 4. lock을 기다리는 동안 같은 원승인이 다른 cancel로 처리됐는지 재확인.
        // - 이번 Release 5 Phase 2-2A의 핵심 분기다.
        // - 다른 cancelPosTrx가 먼저 취소를 확정했다면 새 row를 만들지 않고 ALREADY_CANCELLED로 응답한다.
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

        // 5. 실제 승인된 거래인지 확인.
        // - VAN 취소는 APPROVED 원장에 대해서만 성공할 수 있다.
        // - DECLINED/UNKNOWN 원승인은 취소할 승인 사실이 없으므로 거절 원장을 남긴다.
        if (original.getApprovalStatus() != VanApprovalStatus.APPROVED) {
            return saveDeclined(
                    command,
                    ORIGINAL_NOT_APPROVED,
                    CancelResultCode.ORIGINAL_NOT_APPROVED
            );
        }

        // 6. 요청에 포함된 원승인 정보가 VAN 원장과 일치하는지 확인.
        // - originalPosTrx/attemptSeq로 row를 찾았더라도 금액, VAN 거래번호, 승인번호가 다르면
        //   잘못된 원승인에 대한 취소 요청이다.
        if (isOriginalMismatch(original, command)) {
            return saveDeclined(
                    command,
                    ORIGINAL_MISMATCH,
                    CancelResultCode.ORIGINAL_MISMATCH
            );
        }

        // 7. 신규 정상 취소.
        // - 이 지점에 도달한 요청만 van_cancel 신규 owner가 된다.
        // - 같은 원승인에 대한 후속 요청은 4번 분기에서 이 row를 보고 ALREADY_CANCELLED로 돌아간다.
        return saveCancelled(
                command,
                CancelResultCode.SUCCESS
        );
    }

    private boolean isOriginalMismatch(
            VanApproval original,
            CancelCommand command
    ) {
        // 원승인 식별자만으로는 payload 일치가 보장되지 않는다.
        // Payment 서버가 보낸 원승인 금액/거래번호/승인번호가 VAN 원장과 같은지 확인한다.
        return original.getAmount() != command.amount()
                || original.getVanTrxId().equals(command.originalVanTrxId()) == false
                || original.getApprovalNo().equals(command.originalApprovalNo()) == false
                ;
    }

    private void assertSamePayload(
            VanCancel existingCancel,
            CancelCommand command
    ) {
        // 동일 cancelPosTrx 재요청은 저장된 취소 payload와 완전히 같을 때만 멱등 재응답으로 본다.
        // 하나라도 다르면 클라이언트가 같은 취소 거래번호를 다른 업무 의미로 재사용한 것이다.
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

        // 거절 row는 declineCode를 영속 상태로 저장하고,
        // API 응답에서는 같은 의미를 CancelResultCode로 복원한다.
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
        // 저장된 VAN 취소 원장을 외부 응답 객체로 변환한다.
        // 동일 cancelPosTrx replay 경로에서는 저장 row의 cancelPosTrx가 그대로 응답된다.
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
        // 정상 취소 원장 생성.
        // - vanCancelTrxId는 VAN 내부 취소 거래 추적키다.
        // - cancelApprovalNo는 취소 성공 응답에 포함되는 VAN 취소 승인번호다.
        // - 원승인 row는 수정하지 않고 van_cancel에만 별도 사실을 남긴다.
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
        // 취소 거절도 VAN 취소 원장에 남긴다.
        // 이후 같은 cancelPosTrx replay는 이 row를 기준으로 같은 거절 응답을 재생한다.
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
        // PostgreSQL timestamp와 Java LocalDateTime 비교 흔들림을 줄이기 위해 마이크로초 단위로 맞춘다.
        return LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS);
    }
}
