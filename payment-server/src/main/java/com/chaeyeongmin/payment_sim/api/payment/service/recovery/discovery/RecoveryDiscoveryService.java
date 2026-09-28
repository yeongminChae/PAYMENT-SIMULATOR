package com.chaeyeongmin.payment_sim.api.payment.service.recovery.discovery;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryTaskService;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryCandidate;
import com.chaeyeongmin.payment_sim.infra.repository.RecoveryCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 오래 멈춰 있거나 응답을 확정하지 못한 거래를 찾아 Recovery Task로 등록한다.
 * 거래 상태를 직접 바꾸거나 VAN을 호출하지 않고, 기존 조회 Repository와 등록 Service만 연결한다.
 */
@Service
@RequiredArgsConstructor
public class RecoveryDiscoveryService {

    private final RecoveryCandidateRepository recoveryCandidateRepository;
    private final RecoveryTaskService recoveryTaskService;
    private final Clock clock;

    /**
     * 현재 시각에서 설정된 대기 시간을 빼 조회 기준 시각을 만들고, 승인·취소·망취소 후보를 등록한다.
     *
     * @return 조회된 전체 후보 수와 실제로 새로 등록된 Task 수
     */
    public RecoveryDiscoveryResult discoverAndRegister(
            Duration unknownTimeoutAge,
            Duration staleProcessingAge,
            Duration stalePendingAge
    ) {
        // 세 종류의 후보 조회가 모두 같은 현재 시각을 기준으로 계산되도록 Clock을 한 번만 읽는다.
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime unknownTimeoutBefore = now.minus(unknownTimeoutAge);
        LocalDateTime staleProcessingBefore = now.minus(staleProcessingAge);
        LocalDateTime stalePendingBefore = now.minus(stalePendingAge);

        // 기존 Repository가 가진 상태·시간 조건을 그대로 사용해 거래 종류별 후보를 조회한다.
        List<RecoveryCandidate> approvalCandidates = recoveryCandidateRepository.findApprovalCandidates(
                unknownTimeoutBefore,
                staleProcessingBefore
        );
        List<RecoveryCandidate> cancelCandidates = recoveryCandidateRepository.findCancelCandidates(
                unknownTimeoutBefore,
                stalePendingBefore
        );
        List<RecoveryCandidate> reversalCandidates = recoveryCandidateRepository.findReversalCandidates(
                stalePendingBefore
        );

        // 각 후보를 모두 등록 Service에 전달한다. 중복 여부는 DB UNIQUE 정책이 판단한다.
        int registeredCount = registerAll(approvalCandidates)
                + registerAll(cancelCandidates)
                + registerAll(reversalCandidates);

        // 실제 등록 여부와 관계없이 Repository에서 발견한 모든 후보를 합산한다.
        int discoveredCount = approvalCandidates.size()
                + cancelCandidates.size()
                + reversalCandidates.size();

        return new RecoveryDiscoveryResult(discoveredCount, registeredCount);
    }

    /** 후보를 하나씩 등록하고, 새로 insert된 행의 수를 합산한다. */
    private int registerAll(List<RecoveryCandidate> candidates) {
        int registeredCount = 0;
        for (RecoveryCandidate candidate : candidates) {
            // 이미 같은 대상의 Task가 있으면 registerIfAbsent가 0을 반환한다.
            registeredCount += recoveryTaskService.registerIfAbsent(candidate);
        }
        return registeredCount;
    }
}
