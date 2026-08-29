package com.chaeyeongmin.van_sim.transaction.approval.service.impl;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.ApprovalService;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.exception.ApprovalRequestConflictException;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * VAN 승인 요청의 처리 흐름을 담당한다.
 * <p>
 * - 동일 (posTrx, attemptSeq) 거래의 기존 원장을 먼저 확인한다.
 * - 기존 거래이면 요청 내용의 충돌 여부를 검증하고 기존 결과를 재사용한다.
 * - 신규 거래이면 테스트 Scenario Registry에 따라 APPROVED / DECLINED / UNKNOWN을 결정한다.
 * - VAN 승인 원장을 저장하고 ApprovalResult를 반환한다.
 */
@Service
@Profile("postgres")
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    private final VanApprovalRepository repository;
    private final ApprovalScenarioRegistry scenarioRegistry;
    private final VanTransactionIdGenerator vanTransactionIdGenerator;
    private final ApprovalNumberGenerator approvalNumberGenerator;

    @Override
    @Transactional
    public ApprovalResult processApproval(ApprovalCommand command) {

        // 동일 (posTrx, attemptSeq) 거래가 이미 처리됐는지 확인
        Optional<VanApproval> existing =
                repository.findByPosTrxAndAttemptSeq(
                        command.posTrx(),
                        command.attemptSeq()
                );

        // 승인 재사용 로직
        if (existing.isPresent()) {
            VanApproval existingApproval = existing.get();

            // existing과 command 비교
            if (existingApproval.getAmount() != command.amount()
                    || !existingApproval.getCardBin().equals(command.cardBin())
                    || !existingApproval.getCardLast4().equals(command.cardLast4())
            ) {
                throw new ApprovalRequestConflictException("APPROVAL_REQUEST_CONFLICT");
            }

            return getApprovalResult(existingApproval);

        }

        // 테스트 Scenario 조회, 미설정 시 정상 승인으로 처리
        ApprovalScenario scenario = scenarioRegistry.find(command.posTrx())
                .orElse(new ApprovalScenario(
                        IssuerResult.APPROVED,
                        TransportBehavior.NORMAL
                ));

        // VAN 거래번호 생성
        String vanTrxId = vanTransactionIdGenerator.generate();
        VanApprovalStatus status;
        String approvalNo = null;
        String declineCode = null;

        switch (scenario.issuerResult()) {
            case APPROVED -> {
                status = VanApprovalStatus.APPROVED;
                approvalNo = approvalNumberGenerator.generate();
            }

            case DECLINED -> {
                status = VanApprovalStatus.DECLINED;
                declineCode = "D001";
            }

            case ISSUER_TIMEOUT -> {
                status = VanApprovalStatus.UNKNOWN;
            }

            default -> throw new IllegalStateException(
                    "지원하지 않는 issuer result: " + scenario.issuerResult()
            );
        }

        // 업무 결과가 확정된 시점
        // PostgreSQL TIMESTAMP 정밀도에 맞춰 재조회 시 processedAt 동일성 유지
        LocalDateTime processedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);

        VanApproval entity = getVanApprovalEntity(
                command,
                vanTrxId,
                status,
                approvalNo,
                declineCode,
                processedAt
        );

        // 승인 결과 원장 저장
        repository.save(entity);

        return getApprovalResult(entity);

    }

    private static ApprovalResult getApprovalResult(VanApproval approval) {
        // 상위 계층에 전달할 승인 처리 결과 반환
        return new ApprovalResult(
                approval.getVanTrxId(),
                approval.getPosTrx(),
                approval.getAttemptSeq(),
                approval.getApprovalStatus(),
                approval.getApprovalNo(),
                approval.getDeclineCode(),
                approval.getProcessedAt()
        );
    }

    private static VanApproval getVanApprovalEntity(
            ApprovalCommand command,
            String vanTrxId,
            VanApprovalStatus status,
            String approvalNo,
            String declineCode,
            LocalDateTime processedAt
    ) {
        // 승인 결과를 VAN 원장 Entity로 생성
        return VanApproval.builder()
                .vanTrxId(vanTrxId)
                .posTrx(command.posTrx())
                .attemptSeq(command.attemptSeq())
                .amount(command.amount())
                .cardBin(command.cardBin())
                .cardLast4(command.cardLast4())
                .approvalStatus(status)
                .approvalNo(approvalNo)
                .declineCode(declineCode)
                .processedAt(processedAt)
                .build();
    }

}
