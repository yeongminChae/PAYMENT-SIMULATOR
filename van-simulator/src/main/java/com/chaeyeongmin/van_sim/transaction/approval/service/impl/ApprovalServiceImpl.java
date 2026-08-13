package com.chaeyeongmin.van_sim.transaction.approval.service.impl;

import com.chaeyeongmin.van_sim.control.scenario.approval.model.ApprovalScenario;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.IssuerResult;
import com.chaeyeongmin.van_sim.control.scenario.approval.model.TransportBehavior;
import com.chaeyeongmin.van_sim.control.scenario.approval.registry.ApprovalScenarioRegistry;
import com.chaeyeongmin.van_sim.ledger.approval.entity.VanApproval;
import com.chaeyeongmin.van_sim.ledger.approval.repository.VanApprovalRepository;
import com.chaeyeongmin.van_sim.ledger.approval.status.ApprovalStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.ApprovalService;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import org.springframework.cglib.core.Local;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * [Service]
 * VAN 승인(Approval) 유스케이스의 "오케스트레이션(흐름 제어)"을 담당한다.
 * <p>
 * 이 클래스에서 기억할 핵심:
 * - 동일 posTrx + attemptSeq 조합이 이미 처리됐는지 먼저 확인한다.
 * - 테스트/운영 시나리오 레지스트리에서 승인 결과 정책을 조회한다.
 * - 현재 단계에서는 정상 승인(APPROVED) 흐름만 원장에 저장하고 결과로 반환한다.
 * <p>
 * 책임 범위:
 * - VAN 거래번호/승인번호 생성 위임
 * - VAN 승인 원장 생성 및 저장
 * - 상위 계층에 전달할 ApprovalResult 조립
 */
@Service
@RequiredArgsConstructor
public class ApprovalServiceImpl implements ApprovalService {

    private final VanApprovalRepository repository;
    private final ApprovalScenarioRegistry scenarioRegistry;
    private final VanTransactionIdGenerator vanTransactionIdGenerator;
    private final ApprovalNumberGenerator approvalNumberGenerator;

    @Override
    public ApprovalResult processApproval(ApprovalCommand command) {

        // 동일 (posTrx, attemptSeq) 거래가 이미 처리됐는지 확인
        Optional<VanApproval> existing =
                repository.findByPosTrxAndAttemptSeq(
                        command.posTrx(),
                        command.attemptSeq()
                );

        // 기존 승인 재사용 로직은 다음 단계에서 구현
        if (existing.isPresent()) {
            throw new UnsupportedOperationException(
                    "기존 승인 재사용은 아직 구현하지 않음"
            );
        }

        // 테스트 Scenario 조회, 미설정 시 정상 승인으로 처리
        ApprovalScenario scenario = scenarioRegistry.find(command.posTrx())
                .orElse(new ApprovalScenario(
                        IssuerResult.APPROVED,
                        TransportBehavior.NORMAL
                ));

        // VAN 거래번호 생성
        String vanTrxId = vanTransactionIdGenerator.generate();
        ApprovalStatus status;
        String approvalNo = null;
        String declineCode = null;

        switch (scenario.issuerResult()) {
            case APPROVED -> {
                status = ApprovalStatus.APPROVED;
                approvalNo = approvalNumberGenerator.generate();
            }

            case DECLINED -> {
                status = ApprovalStatus.DECLINED;
                declineCode = "D001";
            }

            case ISSUER_TIMEOUT -> {
                status = ApprovalStatus.UNKNOWN;
            }

            default -> throw new IllegalStateException(
                    "지원하지 않는 issuer result: " + scenario.issuerResult()
            );
        }

        // 업무 결과가 확정된 시점
        LocalDateTime processedAt = LocalDateTime.now();

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

        return getApprovalResult(
                command,
                vanTrxId,
                status,
                approvalNo,
                declineCode,
                processedAt
        );

    }

    private static ApprovalResult getApprovalResult(
            ApprovalCommand command,
            String vanTrxId,
            ApprovalStatus status,
            String approvalNo,
            String declineCode,
            LocalDateTime processedAt
    ) {
        // 상위 계층에 전달할 승인 처리 결과 반환
        return new ApprovalResult(
                vanTrxId,
                command.posTrx(),
                command.attemptSeq(),
                status,
                approvalNo,
                declineCode,
                processedAt
        );
    }

    private static VanApproval getVanApprovalEntity(
            ApprovalCommand command,
            String vanTrxId,
            ApprovalStatus status,
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
